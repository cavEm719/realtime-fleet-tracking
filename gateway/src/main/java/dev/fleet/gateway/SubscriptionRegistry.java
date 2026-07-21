package dev.fleet.gateway;

import dev.fleet.common.TenantAssignment;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class SubscriptionRegistry {
    private record Asset(long tenantId, long assetId) {}

    private final Map<Asset, Set<ClientSession>> assets = new ConcurrentHashMap<>();
    private final Map<Long, Set<ClientSession>> tenants = new ConcurrentHashMap<>();

    public void subscribe(ClientSession client, List<Long> assets, boolean all) {
        validate(client, assets);
        synchronized (client.subscriptions) {
            if (client.isClosed()) {
                return;
            }
            for (var assetId : assets) {
                // Per-key atomic replacement: concurrent subscribers to one asset are never lost.
                this.assets.compute(
                        new Asset(client.tenantId(), assetId),
                        (key, previous) -> added(previous, client));
                client.subscriptions.add(assetId);
            }
            if (all) {
                tenants.compute(
                        client.tenantId(),
                        (key, previous) -> {
                            client.all = true;
                            client.discardPendingPositions();
                            return added(previous, client);
                        });
            }
        }
    }

    public void unsubscribe(ClientSession client, List<Long> assets, boolean all) {
        validate(client, assets);
        synchronized (client.subscriptions) {
            for (var assetId : assets) {
                removeAsset(client, assetId);
            }
            if (all) {
                removeTenant(client);
            }
        }
    }

    public void unregister(ClientSession client) {
        synchronized (client.subscriptions) {
            for (var assetId : List.copyOf(client.subscriptions)) {
                removeAsset(client, assetId);
            }
            removeTenant(client);
        }
    }

    public void forEachTarget(long tenantId, long assetId, Consumer<ClientSession> delivery) {
        // Published sets are immutable: the position hot path needs no locks or set copies.
        var assets = this.assets.getOrDefault(new Asset(tenantId, assetId), Set.of());
        var all = tenants.getOrDefault(tenantId, Set.of());
        assets.forEach(delivery);
        for (var client : all) {
            if (!assets.contains(client)) {
                delivery.accept(client);
            }
        }
    }

    Set<ClientSession> tenantTargets(long tenantId) {
        return tenants.getOrDefault(tenantId, Set.of());
    }

    void forEachAssetTarget(long tenantId, long assetId, Consumer<ClientSession> delivery) {
        var all = tenantTargets(tenantId);
        var assets = this.assets.getOrDefault(new Asset(tenantId, assetId), Set.of());
        for (var client : assets) {
            if (!all.contains(client)) {
                delivery.accept(client);
            }
        }
    }

    private Set<ClientSession> added(Set<ClientSession> previous, ClientSession client) {
        var next = previous == null ? new HashSet<ClientSession>() : new HashSet<>(previous);
        next.add(client);
        return Set.copyOf(next);
    }

    private Set<ClientSession> removed(Set<ClientSession> previous, ClientSession client) {
        var next = new HashSet<>(previous);
        next.remove(client);
        return Set.copyOf(next);
    }

    private void validate(ClientSession client, List<Long> assets) {
        for (var id : assets) {
            if (id == null
                    || id < 100_000_000
                    || id > 999_999_999
                    || TenantAssignment.forMmsi(id) != client.tenantId()) {
                throw new IllegalArgumentException(
                        "Asset belongs to another tenant or is not a valid MMSI");
            }
        }
    }

    private void removeAsset(ClientSession client, long id) {
        assets.computeIfPresent(
                new Asset(client.tenantId(), id),
                (key, previous) -> {
                    var next = removed(previous, client);
                    return next.isEmpty() ? null : next;
                });
        client.subscriptions.remove(id);
    }

    private void removeTenant(ClientSession client) {
        tenants.computeIfPresent(
                client.tenantId(),
                (key, previous) -> {
                    var next = removed(previous, client);
                    return next.isEmpty() ? null : next;
                });
        client.all = false;
    }
}
