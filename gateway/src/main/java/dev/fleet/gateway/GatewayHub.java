package dev.fleet.gateway;

import dev.fleet.common.*;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.*;

public final class GatewayHub implements AutoCloseable {
    private final Map<String, ClientSession> clients = new ConcurrentHashMap<>();

    private static final class TenantPositions {
        final Map<Long, Position> pending = new HashMap<>();
        final Map<Long, Long> lastTimestamps = new HashMap<>();
    }

    private final Map<Long, TenantPositions> tenantPositions = new ConcurrentHashMap<>();
    private final Set<Long> dirtyTenants = ConcurrentHashMap.newKeySet();
    private final Set<ClientSession> dirty = ConcurrentHashMap.newKeySet();
    private final Set<ClientSession> sending = ConcurrentHashMap.newKeySet();
    private final SubscriptionRegistry registry = new SubscriptionRegistry();
    private EventHistory history =
            (tenant, assets, all, cursor) -> {
                throw new IllegalStateException("Event history unavailable");
            };

    void history(EventHistory history) {
        this.history = history;
    }

    interface TenantSessions {
        void active(long tenant);

        void inactive(long tenant);
    }

    private final Map<Long, Integer> tenantCounts = new ConcurrentHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock lifecycleLock =
            new java.util.concurrent.locks.ReentrantLock();
    private TenantSessions tenantSessions =
            new TenantSessions() {
                public void active(long tenant) {}

                public void inactive(long tenant) {}
            };

    void tenantSessions(TenantSessions listener) {
        lifecycleLock.lock();
        try {
            tenantSessions = listener;
            tenantCounts.keySet().forEach(listener::active);
        } finally {
            lifecycleLock.unlock();
        }
    }

    boolean hasTenant(long tenant) {
        return tenantCounts.containsKey(tenant);
    }

    private final GatewaySettings settings;
    private final MeterRegistry metrics;
    private final Executor executor;

    public GatewayHub(GatewaySettings settings, MeterRegistry metrics, Executor executor) {
        this.settings = settings;
        this.metrics = metrics;
        this.executor = executor;
        metrics.gauge("gateway.sessions.open", clients, Map::size);
        metrics.counter("gateway.positions.coalesced");
        metrics.counter("gateway.frames.sent");
        metrics.counter("gateway.sessions.terminated", "reason", "overflow");
        metrics.timer("gateway.delivery.latency");
    }

    public ClientSession open(WebSocketSession socket) {
        long tenantId = ((Number) socket.getAttributes().get("tenantId")).longValue();
        if (tenantId < 1 || tenantId > TenantAssignment.TENANT_COUNT) {
            throw new IllegalArgumentException("Unknown tenant");
        }
        var client =
                new ClientSession(socket, tenantId, settings, metrics, executor, this);
        lifecycleLock.lock();
        try {
            clients.put(client.id(), client);
            int count = tenantCounts.getOrDefault(tenantId, 0);
            tenantCounts.put(tenantId, count + 1);
            try {
                if (count == 0) tenantSessions.active(tenantId);
            } catch (RuntimeException error) {
                clients.remove(client.id(), client);
                if (count == 0) {
                    tenantCounts.remove(tenantId);
                    tenantSessions.inactive(tenantId);
                } else tenantCounts.put(tenantId, count);
                throw error;
            }
        } finally {
            lifecycleLock.unlock();
        }
        return client;
    }

    ClientSession client(String id) {
        return clients.get(id);
    }

    public void subscribe(ClientSession client, List<Long> assets, boolean all) {
        client.commandLock.lock();
        try {
            client.requireLive();
            registry.subscribe(client, assets, all);
        } finally {
            client.commandLock.unlock();
        }
    }

    public void resume(
            ClientSession client, List<Long> assets, boolean all, EventHistory.Cursor cursor) {
        resume(client, assets, all, cursor, List.of());
    }

    public void resume(
            ClientSession client,
            List<Long> assets,
            boolean all,
            EventHistory.Cursor cursor,
            List<String> seenEventIds) {
        var seen = seenEventIds == null ? List.<String>of() : List.copyOf(seenEventIds);
        if (seen.size() > 200 || seen.stream().anyMatch(id -> !id.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("seenEventIds accepts at most 200 SHA-256 hex IDs");
        }
        client.commandLock.lock();
        try {
            boolean subscribed;
            synchronized (client.subscriptions) {
                subscribed = client.all || !client.subscriptions.isEmpty();
            }
            if (subscribed) {
                throw new IllegalArgumentException("Resume requires a fresh session subscription");
            }
            client.beginResume(cursor);
            try {
                registry.subscribe(client, assets, all);
            } catch (RuntimeException error) {
                client.cancelResume();
                throw error;
            }
        } finally {
            client.commandLock.unlock();
        }
        client.control("subscribed", "Catching up fence events");
        executor.execute(
                () -> {
                    try {
                        client.finishResume(
                                history.since(client.tenantId(), assets, all, cursor), seen);
                    } catch (RuntimeException error) {
                        client.failResume(
                                "History unavailable or resume gap exceeds limit; obtain a fresh"
                                        + " snapshot");
                    }
                });
    }

    public void unsubscribe(ClientSession client, List<Long> assets, boolean all) {
        client.commandLock.lock();
        try {
            client.requireLive();
            registry.unsubscribe(client, assets, all);
        } finally {
            client.commandLock.unlock();
        }
    }

    public void unsubscribeAll(ClientSession client) {
        client.commandLock.lock();
        try {
            client.requireLive();
            registry.unregister(client);
        } finally {
            client.commandLock.unlock();
        }
    }

    public void position(Position position) {
        if (!hasTenant(position.tenantId())
                || TenantAssignment.forMmsi(position.assetId()) != position.tenantId()) {
            return;
        }
        Long assetId = position.assetId();
        var tenant =
                tenantPositions.computeIfAbsent(
                        position.tenantId(), unused -> new TenantPositions());
        synchronized (tenant) {
            if (position.positionTs() <= tenant.lastTimestamps.getOrDefault(assetId, -1L)) {
                return;
            }
            tenant.lastTimestamps.put(assetId, position.positionTs());
            if (!registry.tenantTargets(position.tenantId()).isEmpty()) {
                if (tenant.pending.put(assetId, position) != null) {
                    metrics.counter("gateway.positions.coalesced").increment();
                }
                dirtyTenants.add(position.tenantId());
            }
        }
        registry.forEachAssetTarget(
                position.tenantId(),
                position.assetId(),
                client -> client.position(assetId, position));
    }

    public void event(GeofenceEvent event) {
        if (!hasTenant(event.tenantId())
                || TenantAssignment.forMmsi(event.assetId()) != event.tenantId()) {
            return;
        }
        registry.forEachTarget(event.tenantId(), event.assetId(), client -> client.event(event));
    }

    @Scheduled(fixedDelayString = "${gateway.flush-interval-ms:100}")
    public void flush() {
        for (var tenantId : dirtyTenants.toArray(Long[]::new)) {
            if (dirtyTenants.remove(tenantId)) {
                flushTenant(tenantId);
            }
        }
        for (var client : dirty.toArray(ClientSession[]::new)) {
            if (dirty.remove(client)) {
                client.flushPositions();
            }
        }
        // Check only active sends, never scan every connected session.
        for (var client : sending) {
            client.checkSendTime();
        }
    }

    private void flushTenant(long tenantId) {
        var tenant = tenantPositions.get(tenantId);
        List<Position> positions;
        synchronized (tenant) {
            if (tenant.pending.isEmpty()) {
                return;
            }
            positions = List.copyOf(tenant.pending.values());
            tenant.pending.clear();
        }
        var targets = registry.tenantTargets(tenantId);
        if (targets.isEmpty()) {
            return;
        }
        // The immutable frame is serialized once and shared by every tenant-wide mailbox.
        var frame =
                new ClientSession.Frame(
                        new TextMessage(
                                Json.write(Map.of("type", "positions", "positions", positions))),
                        positions);
        for (var client : targets) {
            client.enqueue(frame);
        }
    }

    void markDirty(ClientSession client) {
        dirty.add(client);
    }

    void sending(ClientSession client) {
        sending.add(client);
    }

    void sent(ClientSession client) {
        sending.remove(client);
    }

    void remove(ClientSession client) {
        lifecycleLock.lock();
        try {
            if (!clients.remove(client.id(), client)) return;
            int remaining = tenantCounts.getOrDefault(client.tenantId(), 1) - 1;
            if (remaining == 0) {
                tenantCounts.remove(client.tenantId());
                tenantSessions.inactive(client.tenantId());
            } else tenantCounts.put(client.tenantId(), remaining);
        } finally {
            lifecycleLock.unlock();
        }
        registry.unregister(client);
        dirty.remove(client);
        sending.remove(client);
    }

    @Override
    public void close() {
        for (var client : List.copyOf(clients.values())) {
            client.terminate("closed");
        }
    }
}
