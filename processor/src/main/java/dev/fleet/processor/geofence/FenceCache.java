package dev.fleet.processor.geofence;

import dev.fleet.common.FenceChange;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.*;

public final class FenceCache {
    private static final Logger LOG = LoggerFactory.getLogger(FenceCache.class);
    private final FenceMapper mapper;
    private final FenceIndex index;
    private final MeterRegistry metrics;
    private final Map<Long, Long> versions = new HashMap<>();

    public FenceCache(FenceMapper mapper, FenceIndex index, MeterRegistry metrics) {
        this.mapper = mapper;
        this.index = index;
        this.metrics = metrics;
        index.all().forEach(fence -> versions.put(fence.id(), fence.version()));
        metrics.counter("fence.cache.repairs");
    }

    public synchronized void changed(FenceChange change) {
        if (change.version() <= versions.getOrDefault(change.fenceId(), 0L)) {
            return;
        }
        reload(change.fenceId());
    }

    @Scheduled(
            fixedRateString = "${fence-cache.reconcile-interval-ms:60000}",
            initialDelayString = "${fence-cache.reconcile-interval-ms:60000}")
    public synchronized void reconcile() {
        var revisions = mapper.revisions();
        var present = new HashSet<Long>();
        for (var revision : revisions) {
            present.add(revision.id());
            var current = index.get(revision.id());
            boolean drift =
                    revision.deleted()
                            ? current != null
                            : current == null || current.version() != revision.version();
            if (drift) {
                reload(revision.id());
                repair(revision.id());
            } else {
                versions.put(revision.id(), revision.version());
            }
        }
        for (var fence : index.all()) {
            if (!present.contains(fence.id())) {
                index.remove(fence.id());
                versions.remove(fence.id());
                repair(fence.id());
            }
        }
    }

    private void reload(long id) {
        var row = mapper.find(id);
        if (row == null) {
            index.remove(id);
            versions.remove(id);
        } else {
            if (row.deleted()) {
                index.remove(id);
            } else {
                index.put(row.fence());
            }
            versions.put(id, row.version());
        }
    }

    private void repair(long id) {
        LOG.warn("Repaired fence cache drift for fence {}", id);
        metrics.counter("fence.cache.repairs").increment();
    }
}
