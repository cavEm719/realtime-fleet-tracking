package dev.fleet.gateway;

import dev.fleet.common.*;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.web.socket.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One ordered, bounded mailbox per connection isolates a blocking transport send. */
public final class ClientSession {
    private record Pair(long assetId, long fenceId) {}

    record Frame(TextMessage message, List<Position> positions) {}

    final java.util.concurrent.locks.ReentrantLock commandLock =
            new java.util.concurrent.locks.ReentrantLock();
    final Set<Long> subscriptions = new HashSet<>();
    volatile boolean all;
    private final long tenantId;
    private final WebSocketSession socket;
    private final GatewaySettings settings;
    private final MeterRegistry metrics;
    private final Executor executor;
    private final GatewayHub hub;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object positionsLock = new Object();
    private final Map<Long, Position> pending = new HashMap<>();
    private final Map<Long, Position> latestPositions = new HashMap<>();
    private final Object sendLock = new Object();
    private final ArrayDeque<Frame> mailbox = new ArrayDeque<>();
    private final LinkedHashMap<String, Boolean> eventIds = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Pair, Long> eventTimes = new HashMap<>();

    private final List<GeofenceEvent> resumeBuffer = new ArrayList<>();
    private boolean catchingUp;
    private boolean resumeFailed;
    private int queuedBytes;
    private boolean sending;
    private volatile long sendStartedNanos;

    ClientSession(
            WebSocketSession socket,
            long tenantId,
            GatewaySettings settings,
            MeterRegistry metrics,
            Executor executor,
            GatewayHub hub) {
        this.socket = socket;
        this.tenantId = tenantId;
        this.settings = settings;
        this.metrics = metrics;
        this.executor = executor;
        this.hub = hub;
    }

    public String id() {
        return socket.getId();
    }

    public long tenantId() {
        return tenantId;
    }

    public boolean isClosed() {
        return closed.get();
    }

    void position(Long assetId, Position position) {
        synchronized (positionsLock) {
            Position latest = latestPositions.get(assetId);
            if (isClosed()
                    || all
                    || (latest != null && position.positionTs() <= latest.positionTs())) {
                return;
            }
            // Both maps share the incoming key and Position across recipients; no per-client
            // boxing.
            latestPositions.put(assetId, position);
            if (pending.put(assetId, position) != null) {
                metrics.counter("gateway.positions.coalesced").increment();
            }
            hub.markDirty(this);
        }
    }

    void discardPendingPositions() {
        synchronized (positionsLock) {
            pending.clear();
        }
    }

    void flushPositions() {
        List<Position> positions;
        synchronized (positionsLock) {
            if (isClosed() || pending.isEmpty()) {
                return;
            }
            positions = List.copyOf(pending.values());
            pending.clear();
        }
        enqueue(
                new Frame(
                        new TextMessage(
                                Json.write(Map.of("type", "positions", "positions", positions))),
                        positions));
    }

    void event(GeofenceEvent event) {
        synchronized (sendLock) {
            if (isClosed() || resumeFailed) {
                return;
            }
            if (catchingUp) {
                if (resumeBuffer.size() >= settings.resumeMaxEvents()) {
                    failResume("Live catch-up buffer exceeds limit; obtain a fresh snapshot");
                } else {
                    resumeBuffer.add(event);
                }
                return;
            }
            deliverEvent(event);
        }
    }

    private void deliverEvent(GeofenceEvent event) {
        if (isClosed() || eventIds.get(event.eventId()) != null) {
            return;
        }
        var pair = new Pair(event.assetId(), event.fenceId());
        if (event.positionTs() <= eventTimes.getOrDefault(pair, -1L)) {
            return;
        }
        eventIds.put(event.eventId(), true);
        if (eventIds.size() > settings.eventDedupeSize()) {
            eventIds.remove(eventIds.keySet().iterator().next());
        }
        eventTimes.put(pair, event.positionTs());
        enqueue(
                new Frame(
                        new TextMessage(Json.write(Map.of("type", "fence-event", "event", event))),
                        List.of()));
    }

    void requireLive() {
        synchronized (sendLock) {
            if (catchingUp || resumeFailed) {
                throw new IllegalArgumentException("Resume in progress or fresh snapshot required");
            }
        }
    }

    void beginResume(EventHistory.Cursor cursor) {
        synchronized (sendLock) {
            requireLive();
            catchingUp = true;
        }
    }

    void cancelResume() {
        synchronized (sendLock) {
            catchingUp = false;
            resumeBuffer.clear();
        }
    }

    void finishResume(List<GeofenceEvent> history, List<String> seenEventIds) {
        synchronized (sendLock) {
            if (isClosed() || resumeFailed) return;
            if (history.size() > settings.resumeMaxEvents()) {
                failResume("Resume gap exceeds event limit; obtain a fresh snapshot");
                return;
            }
            var confirmed = new HashSet<>(seenEventIds);
            // Only tenant-scoped durable rows prove that a client-provided ID belongs here.
            // Do not seed ordering timestamps: an unseen older report can be newly committed.
            for (var event : history) {
                if (event.tenantId() == tenantId && confirmed.contains(event.eventId())) {
                    eventIds.put(event.eventId(), true);
                    if (eventIds.size() > settings.eventDedupeSize()) {
                        eventIds.remove(eventIds.keySet().iterator().next());
                    }
                }
            }
            var merged = new ArrayList<GeofenceEvent>(history.size() + resumeBuffer.size());
            merged.addAll(history);
            merged.addAll(resumeBuffer);
            merged.sort(
                    Comparator.comparingLong(GeofenceEvent::createdAtMicros)
                            .thenComparingLong(GeofenceEvent::id));
            for (var event : merged) deliverEvent(event);
            resumeBuffer.clear();
            catchingUp = false;
            control("resume_complete", "Live fence event delivery resumed");
        }
    }

    void failResume(String reason) {
        synchronized (sendLock) {
            if (resumeFailed || isClosed()) return;
            resumeFailed = true;
            catchingUp = false;
            resumeBuffer.clear();
            control("resync_required", reason);
        }
    }

    void control(String type, String message) {
        enqueue(
                new Frame(
                        new TextMessage(Json.write(Map.of("type", type, "message", message))),
                        List.of()));
    }

    void enqueue(Frame frame) {
        synchronized (sendLock) {
            if (isClosed()) {
                return;
            }
            if ((long) queuedBytes + frame.message().getPayloadLength()
                    > settings.bufferSizeBytes()) {
                terminate("overflow");
                return;
            }
            queuedBytes += frame.message().getPayloadLength();
            mailbox.add(frame);
            if (!sending) {
                sending = true;
                executor.execute(this::sendLoop);
            }
        }
    }

    private void sendLoop() {
        while (!isClosed()) {
            Frame frame;
            synchronized (sendLock) {
                frame = mailbox.poll();
                if (frame == null) {
                    sending = false;
                    return;
                }
                queuedBytes -= frame.message().getPayloadLength();
            }
            sendStartedNanos = System.nanoTime();
            hub.sending(this);
            try {
                socket.sendMessage(frame.message());
                if (!isClosed()) {
                    metrics.counter("gateway.frames.sent").increment();
                    for (var position : frame.positions()) {
                        if (position.ingestedAt() > 0) {
                            metrics.timer("gateway.delivery.pipeline.latency")
                                    .record(
                                            Math.max(
                                                    0,
                                                    System.currentTimeMillis()
                                                            - position.ingestedAt()),
                                            TimeUnit.MILLISECONDS);
                        }
                        metrics.timer("gateway.delivery.latency")
                                .record(
                                        Math.max(
                                                0,
                                                System.currentTimeMillis() - position.emittedAt()),
                                        TimeUnit.MILLISECONDS);
                    }
                }
            } catch (IOException | RuntimeException error) {
                terminate("transport");
            } finally {
                sendStartedNanos = 0;
                hub.sent(this);
            }
        }
    }

    void checkSendTime() {
        long started = sendStartedNanos;
        if (started != 0
                && System.nanoTime() - started
                        > TimeUnit.MILLISECONDS.toNanos(settings.sendTimeLimitMs())) {
            terminate("send_time");
        }
    }

    void terminate(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (!reason.equals("closed")) {
            metrics.counter("gateway.sessions.terminated", "reason", reason).increment();
        }
        synchronized (positionsLock) {
            pending.clear();
            latestPositions.clear();
        }
        synchronized (sendLock) {
            mailbox.clear();
            queuedBytes = 0;
            eventIds.clear();
            eventTimes.clear();
            resumeBuffer.clear();
        }
        hub.remove(this);
        // Closing a blocked transport must not block the flusher or a Kafka listener.
        executor.execute(() -> closeTransport(reason));
    }

    private void closeTransport(String reason) {
        try {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason(reason));
        } catch (IOException error) {
            /* The registry is already detached from the failed transport. */
        }
    }
}
