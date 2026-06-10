package dev.fleet.common;

import java.util.List;

public record IngestBatch(long tenantId, List<IngestPoint> positions) {
    public IngestBatch {
        if (tenantId <= 0
                || positions == null
                || positions.isEmpty()
                || positions.size() > 1000
                || positions.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Expected tenant and 1..1000 positions");
        }
        positions = List.copyOf(positions);
    }
}
