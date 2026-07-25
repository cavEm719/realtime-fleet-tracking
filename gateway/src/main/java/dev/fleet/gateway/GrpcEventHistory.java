package dev.fleet.gateway;

import dev.fleet.common.GeofenceEvent;
import dev.fleet.common.rpc.*;

import io.grpc.Channel;
import io.grpc.Deadline;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class GrpcEventHistory implements EventHistory {
    private final FleetDataGrpc.FleetDataBlockingStub api;
    private final int maxEvents;
    private final Duration timeout;
    private final Duration lookBack;

    GrpcEventHistory(Channel channel, int maxEvents, Duration timeout) {
        this(channel, maxEvents, timeout, Duration.ofSeconds(5));
    }

    GrpcEventHistory(Channel channel, int maxEvents, Duration timeout, Duration lookBack) {
        if (maxEvents < 1
                || timeout.isNegative()
                || timeout.isZero()
                || lookBack.isNegative()
                || lookBack.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("Resume limits must be positive");
        }
        this.api = FleetDataGrpc.newBlockingStub(channel);
        this.maxEvents = maxEvents;
        this.timeout = timeout;
        this.lookBack = lookBack;
    }

    @Override
    public List<GeofenceEvent> since(long tenant, List<Long> assets, boolean all, Cursor after) {
        var deadline = Deadline.after(timeout.toNanos(), TimeUnit.NANOSECONDS);
        var anchor =
                EventCursor.newBuilder()
                        .setCreatedAtMicros(after.createdAtMicros())
                        .setId(after.id())
                        .build();
        EventCursor pageAfter = null;
        var result = new ArrayList<GeofenceEvent>();
        while (true) {
            int size = Math.min(50, maxEvents - result.size() + 1);
            var request =
                    EventsSinceRequest.newBuilder()
                            .setTenantId(tenant)
                            .addAllAssetIds(assets)
                            .setAll(all)
                            .setAfter(anchor)
                            .setLookBackMicros(lookBack.toNanos() / 1000)
                            .setLimit(size);
            if (pageAfter != null) request.setPageAfter(pageAfter);
            var page = api.withDeadline(deadline).listEventsSince(request.build());
            for (var event : page.getEventsList()) {
                result.add(
                        new GeofenceEvent(
                                event.getEventId(),
                                tenant,
                                event.getAssetId(),
                                event.getFenceId(),
                                event.getFenceVersion(),
                                event.getPositionTs(),
                                GeofenceEvent.Type.valueOf(event.getType().name()),
                                0,
                                0,
                                event.getCreatedAtMicros(),
                                event.getId()));
            }
            if (result.size() > maxEvents) {
                throw new IllegalStateException("Resume gap exceeds event limit");
            }
            if (page.getEventsCount() < size) {
                return List.copyOf(result);
            }
            if (pageAfter != null && page.getNext().equals(pageAfter)) {
                throw new IllegalStateException("Resume cursor did not advance");
            }
            pageAfter = page.getNext();
        }
    }
}
