package dev.fleet.loadtest;

import dev.fleet.common.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class WebSocketLoad {
    private final AtomicInteger connected = new AtomicInteger();
    private final AtomicInteger subscribedCount = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger terminated = new AtomicInteger();
    private final AtomicInteger slowTerminated = new AtomicInteger();
    private final LongAdder messages = new LongAdder();
    private final LatencyHistogram pipeline = new LatencyHistogram();
    private final LatencyHistogram latency = new LatencyHistogram();
    private final LongAdder missingPipeline = new LongAdder();
    private final AtomicBoolean measuring = new AtomicBoolean();
    private final AtomicInteger callbacks = new AtomicInteger();
    private final LatencyHistogram connectionTime = new LatencyHistogram();
    private Path startSignal;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Queue<WebSocket> sockets = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

    private final class Client implements WebSocket.Listener {
        final boolean slow;
        final CompletableFuture<Void> subscribed = new CompletableFuture<>();
        final StringBuilder fragments = new StringBuilder();
        final AtomicBoolean ended = new AtomicBoolean();
        final AtomicBoolean opened = new AtomicBoolean();

        Client(boolean slow) {
            this.slow = slow;
        }

        @Override
        public void onOpen(WebSocket socket) {
            opened.set(true);
            connected.incrementAndGet();
            sockets.add(socket);
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            if (fragments.length() + data.length() > 1048576) {
                finish("Frame exceeded 1 MiB");
                socket.abort();
                return CompletableFuture.completedFuture(null);
            }
            fragments.append(data);
            if (!last) {
                socket.request(1);
                return CompletableFuture.completedFuture(null);
            }
            callbacks.incrementAndGet();
            try {
                boolean collect = measuring.get();
                String type =
                        FrameReader.read(
                                fragments.toString(),
                                System.currentTimeMillis(),
                                collect ? pipeline : null,
                                latency,
                                missingPipeline);
                fragments.setLength(0);
                if (type.equals("subscribed")) {
                    subscribed.complete(null);
                } else if (type.equals("error")) {
                    subscribed.completeExceptionally(
                            new IllegalArgumentException("Subscription rejected"));
                } else if (collect && (type.equals("positions") || type.equals("fence-event"))) {
                    messages.increment();
                }
                if (!slow) {
                    socket.request(1);
                }
            } catch (RuntimeException error) {
                finish(error.toString());
                socket.abort();
            } finally {
                callbacks.decrementAndGet();
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            finish("close " + status + " " + reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            finish(error.toString());
        }

        private void finish(String reason) {
            subscribed.completeExceptionally(new IllegalStateException(reason));
            if (opened.get() && !closing.get() && ended.compareAndSet(false, true)) {
                terminated.incrementAndGet();
                if (slow) slowTerminated.incrementAndGet();
                if (errors.size() < 20) errors.add(reason);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && (args[0].equals("lag") || args[0].equals("reset-kafka"))) {
            LagMonitor.run(args);
            return;
        }
        if (args.length < 3 || args.length > 9) {
            throw new IllegalArgumentException(
                    "Usage: clients seconds assets.json [wsUrl] [allPercent=100] [slowPercent=0]"
                            + " [connectConcurrency=200] [outputDirectory=.] [startSignalFile] ");
        }
        int clients = Integer.parseInt(args[0]);
        int seconds = Integer.parseInt(args[1]);
        String url = args.length > 3 ? args[3] : "ws://localhost:8082/ws";
        int all = args.length > 4 ? Integer.parseInt(args[4]) : 100;
        int slow = args.length > 5 ? Integer.parseInt(args[5]) : 0;
        int concurrency = args.length > 6 ? Integer.parseInt(args[6]) : 200;
        Path output = Path.of(args.length > 7 ? args[7] : ".");
        if (clients < 1
                || seconds < 1
                || all < 0
                || all > 100
                || slow < 0
                || slow > 100
                || concurrency < 1) throw new IllegalArgumentException("Invalid load options");
        var assets = Json.read(Files.readString(Path.of(args[2])), List.class);
        var byTenant = new HashMap<Long, List<Long>>();
        for (Object value : assets) {
            long asset = ((Number) value).longValue();
            byTenant.computeIfAbsent(TenantAssignment.forMmsi(asset), unused -> new ArrayList<>())
                    .add(asset);
        }
        var load = new WebSocketLoad();
        load.startSignal = args.length > 8 ? Path.of(args[8]) : null;
        load.run(clients, seconds, url, all, slow, concurrency, output, byTenant);
    }

    private void run(
            int count,
            int seconds,
            String url,
            int allPercent,
            int slowPercent,
            int concurrency,
            Path output,
            Map<Long, List<Long>> assets)
            throws Exception {
        Files.createDirectories(output);
        long started = System.nanoTime();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var http =
                        HttpClient.newBuilder()
                                .executor(workers)
                                .connectTimeout(Duration.ofSeconds(10))
                                .build()) {
            var permits = new Semaphore(concurrency);
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < count; i++) {
                int index = i;
                tasks.add(
                        workers.submit(
                                () -> {
                                    boolean acquired = false;
                                    long begin = System.nanoTime();
                                    try {
                                        permits.acquire();
                                        acquired = true;
                                        var listener = new Client(index % 100 < slowPercent);
                                        long tenant = 1 + index % 3;
                                        var socket =
                                                http.newWebSocketBuilder()
                                                        .connectTimeout(Duration.ofSeconds(10))
                                                        .buildAsync(
                                                                URI.create(
                                                                        url
                                                                                + "?tenantId="
                                                                                + tenant
                                                                                + "&userId=load-"
                                                                                + index),
                                                                listener)
                                                        .get(15, TimeUnit.SECONDS);
                                        boolean all = (index * 37L) % 100 < allPercent;
                                        var owned = assets.getOrDefault(tenant, List.of());
                                        if (!all && owned.isEmpty())
                                            throw new IllegalArgumentException(
                                                    "No assets for tenant " + tenant);
                                        String command =
                                                all
                                                        ? Json.write(
                                                                Map.of(
                                                                        "type",
                                                                        "subscribe",
                                                                        "all",
                                                                        true))
                                                        : Json.write(
                                                                Map.of(
                                                                        "type",
                                                                        "subscribe",
                                                                        "assetIds",
                                                                        List.of(
                                                                                owned.get(
                                                                                        index
                                                                                                % owned
                                                                                                        .size()))));
                                        socket.sendText(command, true).get(10, TimeUnit.SECONDS);
                                        listener.subscribed.get(15, TimeUnit.SECONDS);
                                        subscribedCount.incrementAndGet();
                                        connectionTime.record(
                                                (System.nanoTime() - begin) / 1000000);
                                    } catch (Exception error) {
                                        failed.incrementAndGet();
                                        if (errors.size() < 20) errors.add(error.toString());
                                    } finally {
                                        if (acquired) permits.release();
                                    }
                                }));
            }
            for (var task : tasks) task.get();
            System.out.println(
                    Json.write(
                            Map.of(
                                    "event",
                                    "ready",
                                    "requested",
                                    count,
                                    "connected",
                                    connected.get(),
                                    "failed",
                                    failed.get(),
                                    "connectSeconds",
                                    (System.nanoTime() - started) / 1e9)));
            System.out.flush();
            if (startSignal != null) {
                long barrierDeadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
                while (!Files.exists(startSignal)) {
                    if (System.nanoTime() >= barrierDeadline)
                        throw new TimeoutException("Measurement start signal missing");
                    Thread.sleep(100);
                }
            }
            this.measuring.set(true);
            long measuring = System.nanoTime();
            Thread.sleep(Duration.ofSeconds(seconds));
            double elapsed = (System.nanoTime() - measuring) / 1e9;
            this.measuring.set(false);
            closing.set(true);
            sockets.forEach(WebSocket::abort);
            long drain = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (callbacks.get() != 0 && System.nanoTime() < drain) {
                Thread.sleep(1);
            }
            if (callbacks.get() != 0) {
                throw new IllegalStateException("Latency callbacks did not stop before snapshot");
            }
            var report = new LinkedHashMap<String, Object>();
            report.put("requested", count);
            report.put("connected", connected.get());
            report.put("subscribed", subscribedCount.get());
            report.put("failed", failed.get());
            report.put("terminated", terminated.get());
            report.put("slowTerminatedObserved", slowTerminated.get());
            report.put("allPercent", allPercent);
            report.put("slowPercent", slowPercent);
            report.put("elapsedSeconds", elapsed);
            report.put("messages", messages.sum());
            report.put("messagesPerSecond", messages.sum() / elapsed);
            report.put("pipelineSamples", pipeline.count());
            report.put("pipelineP50Ms", pipeline.percentile(.50));
            report.put("pipelineP95Ms", pipeline.percentile(.95));
            report.put("pipelineP99Ms", pipeline.percentile(.99));
            report.put("pipelineOverflow", pipeline.overflow());
            report.put("pipelineNegative", pipeline.negative());
            report.put("pipelineMissing", missingPipeline.sum());
            report.put("primaryLatency", "receipt minus server ingestedAt");
            report.put("secondaryLatency", "receipt minus generator emittedAt");
            report.put(
                    "retention",
                    "bounded histograms; streaming timestamps; no per-message objects retained");
            report.put("positionSamples", latency.count());
            report.put("p50Ms", latency.percentile(.50));
            report.put("p95Ms", latency.percentile(.95));
            report.put("p99Ms", latency.percentile(.99));
            report.put("latencyOverflow", latency.overflow());
            report.put("latencyNegative", latency.negative());
            report.put("connectionP99Ms", connectionTime.percentile(.99));
            report.put("errors", List.copyOf(errors));
            Files.writeString(output.resolve("clients.json"), Json.write(report) + "\n");
            latency.save(output.resolve("latency.csv"));
            pipeline.save(output.resolve("pipeline-latency.csv"));
            connectionTime.save(output.resolve("connection-latency.csv"));
            System.out.println(Json.write(report));
        }
    }
}
