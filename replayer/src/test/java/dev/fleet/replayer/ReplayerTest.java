package dev.fleet.replayer;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ReplayerTest {
    @Test
    void vesselAlwaysUsesOneLaneAndLanesArePopulated() {
        var seen = new java.util.HashSet<Integer>();
        for (long mmsi = 366000000; mmsi < 366000100; mmsi++) {
            int lane = Replayer.laneFor(mmsi, 8);
            assertThat(Replayer.laneFor(mmsi, 8)).isEqualTo(lane);
            seen.add(lane);
        }
        assertThat(seen).hasSize(8);
    }

    @Test
    void allLanesShareAPacedBatchScheduleAndMaxRateCanRemainUnlimited() {
        var pacer = new RatePacer(1000);
        assertThat(pacer.reserve(200, 1000)).isEqualTo(1000);
        assertThat(pacer.reserve(100, 1000)).isEqualTo(200001000);
        assertThat(pacer.reserve(200, 1000000000)).isEqualTo(1000000000);
        assertThat(new RatePacer(0).reserve(200, 10)).isEqualTo(10);
    }

    @Test
    void switchingSpeedPreservesAContinuousSchedule() {
        assertThat(Replayer.dueNanos(100000, 10, 10, 100)).isEqualTo(10000000000L);
        assertThat(Replayer.dueNanos(200000, 10, 10, 100)).isEqualTo(11000000000L);
        assertThat(Replayer.dueNanos(10000, 10, 0, 100)).isEqualTo(1000000000L);
    }

    @Test
    void parallelHttpLanesPreserveEveryVesselsOrder() throws Exception {
        var seen = new java.util.concurrent.ConcurrentHashMap<Long, java.util.List<Long>>();
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("localhost", 0), 0);
        var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext(
                "/ingest",
                exchange -> {
                    var batch =
                            dev.fleet.common.Json.read(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8),
                                    dev.fleet.common.IngestBatch.class);
                    for (var point : batch.positions()) {
                        var timestamps =
                                seen.computeIfAbsent(
                                        point.mmsi(),
                                        unused ->
                                                java.util.Collections.synchronizedList(
                                                        new java.util.ArrayList<>()));
                        timestamps.add(point.positionTs());
                    }
                    exchange.sendResponseHeaders(202, 0);
                    exchange.close();
                });
        server.start();
        var file = java.nio.file.Files.createTempFile("lane-order", ".csv");
        try {
            var csv = new StringBuilder("MMSI,BaseDateTime,LAT,LON,SOG,COG,Heading\n");
            for (int second = 0; second < 30; second++) {
                for (long asset = 366000000; asset < 366000006; asset++) {
                    csv.append(asset)
                            .append(",2023-01-01T00:00:")
                            .append(String.format("%02d", second))
                            .append(",33.73,-118.2625,0,0,0\n");
                }
            }
            java.nio.file.Files.writeString(file, csv);
            Replayer.main(
                    new String[] {
                        file.toString(),
                        "100",
                        "3",
                        "http://localhost:" + server.getAddress().getPort(),
                        "4",
                        "max"
                    });
            assertThat(seen).hasSize(6);
            for (var timestamps : seen.values()) {
                assertThat(timestamps).hasSize(30).isSorted();
                assertThat(new java.util.HashSet<>(timestamps)).hasSize(30);
            }
        } finally {
            server.stop(0);
            workers.close();
            java.nio.file.Files.delete(file);
        }
    }
}
