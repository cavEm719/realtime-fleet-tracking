package dev.fleet.replayer;

import dev.fleet.common.*;

import org.apache.commons.csv.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class Replayer {
    private record SourcePoint(
            long mmsi, long ts, double lat, double lon, Double sog, Double cog, Double heading) {}

    private record ReplayOptions(
            double speed,
            int batchSize,
            String api,
            boolean maxRate,
            double switchAfterSeconds,
            double burstSpeed,
            RatePacer pacer,
            java.util.concurrent.atomic.LongAdder rateRetries,
            java.util.concurrent.atomic.LongAdder unavailableRetries) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 9) {
            System.err.println(
                    "Usage: java -jar replayer-*-exec.jar <csv> [speed=60] [batchSize=200]"
                        + " [api=http://localhost:8080] [lanes=1] [mode=speed|max]"
                        + " [switchAfterSeconds=0] [burstSpeed=100] [offeredPositionsPerSecond=0]");
            System.exit(2);
        }
        double speed = args.length > 1 ? Double.parseDouble(args[1]) : 60;
        int batchSize = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        String api = args.length > 3 ? args[3] : "http://localhost:8080";
        int lanes = args.length > 4 ? Integer.parseInt(args[4]) : 1;
        String mode = args.length > 5 ? args[5] : "speed";
        double switchAfter = args.length > 6 ? Double.parseDouble(args[6]) : 0;
        double burstSpeed = args.length > 7 ? Double.parseDouble(args[7]) : 100;
        if (lanes < 1
                || lanes > 256
                || (!mode.equals("speed") && !mode.equals("max"))
                || !Double.isFinite(switchAfter)
                || switchAfter < 0
                || !Double.isFinite(burstSpeed)
                || burstSpeed <= 0)
            throw new IllegalArgumentException("Invalid lane/mode/burst options");
        if (!Double.isFinite(speed) || speed <= 0 || batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException(
                    "speed must be finite and positive; batchSize 1..1000");
        }
        int offeredRate = args.length > 8 ? Integer.parseInt(args[8]) : 0;
        var rateRetries = new java.util.concurrent.atomic.LongAdder();
        var unavailableRetries = new java.util.concurrent.atomic.LongAdder();
        List<SourcePoint> points = read(Path.of(args[0]));
        if (points.isEmpty()) {
            throw new IllegalArgumentException("No valid AIS positions");
        }
        points.sort(Comparator.comparingLong(SourcePoint::ts));
        var options =
                new ReplayOptions(
                        speed,
                        batchSize,
                        api,
                        mode.equals("max"),
                        switchAfter,
                        burstSpeed,
                        new RatePacer(offeredRate),
                        rateRetries,
                        unavailableRetries);
        var lanePoints = new ArrayList<List<SourcePoint>>();
        for (int lane = 0; lane < lanes; lane++) lanePoints.add(new ArrayList<>());
        for (var point : points) lanePoints.get(laneFor(point.mmsi(), lanes)).add(point);
        long start = System.nanoTime();
        long firstTs = points.getFirst().ts();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<Long>>();
            for (var lane : lanePoints)
                results.add(workers.submit(() -> replay(lane, options, client, start, firstTs)));
            long sent = 0;
            for (var result : results) sent += result.get();
            double seconds = (System.nanoTime() - start) / 1e9;
            System.out.println(
                    Json.write(
                            Map.of(
                                    "acknowledged",
                                    sent,
                                    "elapsedSeconds",
                                    seconds,
                                    "positionsPerSecond",
                                    sent / seconds,
                                    "lanes",
                                    lanes,
                                    "mode",
                                    mode,
                                    "offeredPositionsPerSecond",
                                    offeredRate,
                                    "http429Retries",
                                    rateRetries.sum(),
                                    "http503Retries",
                                    unavailableRetries.sum())));
        }
    }

    static int laneFor(long mmsi, int lanes) {
        return Math.floorMod(Long.hashCode(mmsi), lanes);
    }

    static long dueNanos(long deltaMs, double speed, double switchAfter, double burstSpeed) {
        double switchMs = switchAfter * speed * 1000;
        double seconds =
                switchAfter > 0 && deltaMs > switchMs
                        ? switchAfter + (deltaMs - switchMs) / (burstSpeed * 1000)
                        : deltaMs / (speed * 1000);
        return (long) (seconds * 1e9);
    }

    private static long replay(
            List<SourcePoint> points,
            ReplayOptions options,
            HttpClient client,
            long start,
            long firstTs)
            throws Exception {
        Map<Long, List<IngestPoint>> batches = new LinkedHashMap<>();
        long lastFlush = start;
        long sent = 0;
        for (SourcePoint point : points) {
            long dueNanos =
                    options.maxRate()
                            ? 0
                            : dueNanos(
                                    point.ts() - firstTs,
                                    options.speed(),
                                    options.switchAfterSeconds(),
                                    options.burstSpeed());
            while (dueNanos > System.nanoTime() - start) {
                if (System.nanoTime() - lastFlush >= 250_000_000L && !batches.isEmpty()) {
                    sent += flush(client, options, batches);
                    lastFlush = System.nanoTime();
                }
                long remaining = dueNanos - (System.nanoTime() - start);
                if (remaining > 0) {
                    Thread.sleep(Duration.ofNanos(Math.min(remaining, 50_000_000L)));
                }
            }
            var batch =
                    batches.computeIfAbsent(
                            TenantAssignment.forMmsi(point.mmsi()), unused -> new ArrayList<>());
            batch.add(
                    new IngestPoint(
                            point.mmsi(),
                            point.ts(),
                            point.lat(),
                            point.lon(),
                            point.sog(),
                            point.cog(),
                            point.heading(),
                            System.currentTimeMillis()));
            if (batch.size() >= options.batchSize()
                    || System.nanoTime() - lastFlush >= 250_000_000L) {
                sent += flush(client, options, batches);
                lastFlush = System.nanoTime();
            }
        }
        return sent + flush(client, options, batches);
    }

    private static long flush(
            HttpClient client, ReplayOptions options, Map<Long, List<IngestPoint>> batches)
            throws Exception {
        long count = 0;
        for (var entry : batches.entrySet()) {
            send(client, options, new IngestBatch(entry.getKey(), entry.getValue()));
            count += entry.getValue().size();
        }
        batches.clear();
        return count;
    }

    private static void send(HttpClient client, ReplayOptions options, IngestBatch batch)
            throws Exception {
        options.pacer().await(batch.positions().size());
        String payload = Json.write(batch);
        for (int attempt = 0; attempt < 20; attempt++) {
            var request =
                    HttpRequest.newBuilder(URI.create(options.api() + "/ingest"))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build();
            try {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 202) {
                    return;
                }
                if (response.statusCode() != 429 && response.statusCode() != 503) {
                    throw new IllegalStateException(
                            "Ingest rejected: " + response.statusCode() + " " + response.body());
                }
                if (response.statusCode() == 429) {
                    options.rateRetries().increment();
                } else {
                    options.unavailableRetries().increment();
                }
                long delay =
                        Long.parseLong(response.headers().firstValue("Retry-After").orElse("1"));
                Thread.sleep(Duration.ofSeconds(Math.min(60, Math.max(1, delay))));
            } catch (IOException e) {
                if (attempt == 19) {
                    throw e;
                }
                Thread.sleep(Duration.ofSeconds(1));
            }
        }
        throw new IllegalStateException(
                "Ingest unavailable after 20 attempts; last batch acceptance uncertain");
    }

    private static List<SourcePoint> read(Path path) throws IOException {
        var points = new ArrayList<SourcePoint>();
        long skipped = 0;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
                var csv =
                        CSVFormat.DEFAULT
                                .builder()
                                .setHeader()
                                .setSkipHeaderRecord(true)
                                .get()
                                .parse(reader)) {
            for (CSVRecord row : csv) {
                try {
                    points.add(parseRow(row));
                } catch (IllegalArgumentException | DateTimeException e) {
                    skipped++;
                }
            }
        }
        System.out.printf(
                "Loaded %,d points; skipped %,d invalid/out-of-GEO-bounds rows%n",
                points.size(), skipped);
        return points;
    }

    private static SourcePoint parseRow(CSVRecord row) {
        long mmsi = Long.parseLong(row.get("MMSI"));
        String time = row.get("BaseDateTime");
        long ts =
                time.endsWith("Z")
                        ? Instant.parse(time).toEpochMilli()
                        : LocalDateTime.parse(time).toInstant(ZoneOffset.UTC).toEpochMilli();
        double lat = Double.parseDouble(row.get("LAT"));
        double lon = Double.parseDouble(row.get("LON"));
        Double sog = optional(row.get("SOG"), 102.3);
        Double cog = optional(row.get("COG"), 360);
        Double heading = optional(row.get("Heading"), 511);
        new IngestPoint(mmsi, ts, lat, lon, sog, cog, heading, 0);
        return new SourcePoint(mmsi, ts, lat, lon, sog, cog, heading);
    }

    private static Double optional(String value, double missingValue) {
        if (value.isBlank()) {
            return null;
        }
        double number = Double.parseDouble(value);
        return number == missingValue ? null : number;
    }

    private Replayer() {}
}
