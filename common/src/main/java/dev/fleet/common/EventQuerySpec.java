package dev.fleet.common;

import dev.fleet.common.rpc.EventQueryRequest;

import java.util.List;
import java.util.Set;

/** The validated event query is shared by SQL compilation and the execution receipt. */
public record EventQuerySpec(
        long tenantId,
        String metric,
        String eventType,
        List<Long> ports,
        List<Long> vessels,
        long fromTs,
        long toTs,
        String groupBy) {
    public EventQuerySpec {
        if (tenantId <= 0
                || metric == null
                || groupBy == null
                || !Set.of("event_count", "distinct_vessels").contains(metric)
                || !Set.of("port", "vessel", "day", "type", "none").contains(groupBy)) {
            throw new IllegalArgumentException("Unsupported metric or grouping");
        }
        if (eventType != null && !Set.of("ENTER", "EXIT").contains(eventType)) {
            throw new IllegalArgumentException("Unsupported event type");
        }
        ports = identifiers(ports);
        vessels = identifiers(vessels);
        if (fromTs < 0 || toTs < fromTs || toTs - fromTs > 366L * 86400000) {
            throw new IllegalArgumentException("Time range must be within 366 days");
        }
    }

    private static List<Long> identifiers(List<Long> ids) {
        if (ids == null || ids.size() > 50 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("At most 50 positive identifiers");
        }
        return ids.stream().distinct().sorted().toList();
    }

    public static EventQuerySpec from(EventQueryRequest request) {
        if (!request.hasFromTs() || !request.hasToTs()) {
            throw new IllegalArgumentException("Both time bounds are required");
        }
        return new EventQuerySpec(
                request.getTenantId(),
                request.getMetric(),
                request.hasEventType() ? request.getEventType() : null,
                request.getPortsList(),
                request.getVesselsList(),
                request.getFromTs(),
                request.getToTs(),
                request.getGroupBy());
    }

    public EventQueryRequest request() {
        var result =
                EventQueryRequest.newBuilder()
                        .setTenantId(tenantId)
                        .setMetric(metric)
                        .addAllPorts(ports)
                        .addAllVessels(vessels)
                        .setFromTs(fromTs)
                        .setToTs(toTs)
                        .setGroupBy(groupBy);
        if (eventType != null) result.setEventType(eventType);
        return result.build();
    }
}
