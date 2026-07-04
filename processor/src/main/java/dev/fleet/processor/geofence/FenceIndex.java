package dev.fleet.processor.geofence;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Each company has a few ports, so positions check that company's whole list. */
public final class FenceIndex {
    private record Snapshot(Map<Long, List<Fence>> tenants, Map<Long, Fence> fences) {}

    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());
    private final ReentrantLock writerLock = new ReentrantLock();

    /** Copy on write keeps the per-position lookup path free of locking. */
    public void put(Fence fence) {
        writerLock.lock();
        try {
            var fences = new HashMap<>(snapshot.fences());
            fences.put(fence.id(), fence);
            snapshot = buildSnapshot(fences);
        } finally {
            writerLock.unlock();
        }
    }

    public void remove(long fenceId) {
        writerLock.lock();
        try {
            var fences = new HashMap<>(snapshot.fences());
            if (fences.remove(fenceId) != null) {
                snapshot = buildSnapshot(fences);
            }
        } finally {
            writerLock.unlock();
        }
    }

    private Snapshot buildSnapshot(Map<Long, Fence> fences) {
        var tenants = new HashMap<Long, List<Fence>>();
        fences.values().stream()
                .sorted(Comparator.comparingLong(Fence::id))
                .forEach(f -> tenants.computeIfAbsent(f.tenantId(), unused -> new ArrayList<>()).add(f));
        tenants.replaceAll((tenant, list) -> List.copyOf(list));
        return new Snapshot(Map.copyOf(tenants), Map.copyOf(fences));
    }

    public Fence get(long fenceId) {
        return snapshot.fences().get(fenceId);
    }

    public List<Fence> forTenant(long tenantId) {
        return snapshot.tenants().getOrDefault(tenantId, List.of());
    }

    public List<Fence> all() {
        return List.copyOf(snapshot.fences().values());
    }
}
