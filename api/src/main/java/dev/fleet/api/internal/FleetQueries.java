package dev.fleet.api.internal;

import dev.fleet.api.copilot.CopilotStore;
import dev.fleet.common.*;

import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class FleetQueries {
    private final CopilotStore store;
    private final StringRedisTemplate redis;
    private final double maxRadiusM;

    public FleetQueries(
            CopilotStore store,
            StringRedisTemplate redis,
            @org.springframework.beans.factory.annotation.Value("${internal.max-radius-m:50000}")
                    double maxRadiusM) {
        this.store = store;
        this.redis = redis;
        if (!Double.isFinite(maxRadiusM) || maxRadiusM <= 0 || maxRadiusM > 100000) {
            throw new IllegalArgumentException("Maximum query radius must be within 100000 meters");
        }
        this.maxRadiusM = maxRadiusM;
    }

    public record AssetStatus(
            long assetId, Position position, List<CopilotStore.ConfirmedState> confirmedStates) {}

    public record NearbyVessel(long assetId, double distanceM) {}

    public static int resultLimit(Integer requested) {
        if (requested != null && requested < 1) {
            throw new IllegalArgumentException("Limit must be positive");
        }
        return requested == null ? 50 : Math.min(50, requested);
    }

    public AssetStatus getAssetStatus(long assetId, long tenantId, int limit) {
        requireAsset(tenantId, assetId);
        Object payload = redis.opsForHash().get(RedisKeys.latest(tenantId, assetId), "position");
        Position position = payload == null ? null : Json.read(payload.toString(), Position.class);
        return new AssetStatus(assetId, position, store.states(tenantId, assetId, limit));
    }

    public List<CopilotStore.Event> listEvents(
            Long assetId, Long fenceId, Long fromTs, Long toTs, Integer limit, long tenantId) {
        long tenant = tenantId;
        if (assetId != null) requireAsset(tenant, assetId);
        if (fenceId != null && !store.ownsFence(tenant, positive(fenceId))) {
            throw new IllegalArgumentException("Fence not found");
        }
        if ((fromTs != null && fromTs < 0)
                || (toTs != null && toTs < 0)
                || (fromTs != null && toTs != null && fromTs > toTs)
                || (limit != null && limit < 1)) {
            throw new IllegalArgumentException("Invalid event range or limit");
        }
        return store.events(
                tenant, assetId, fenceId, fromTs, toTs, limit == null ? 50 : Math.min(50, limit));
    }

    public dev.fleet.common.rpc.EventsSinceResult eventsSince(
            dev.fleet.common.rpc.EventsSinceRequest request) {
        if ((request.getAll() && !request.getAssetIdsList().isEmpty())
                || (!request.getAll() && request.getAssetIdsList().isEmpty())
                || request.getAssetIdsCount() > 1000
                || (request.hasAfter() && !request.getAfterEventId().isEmpty())
                || (!request.hasAfter() && request.getAfterEventId().isEmpty())) {
            throw new IllegalArgumentException("Invalid resume scope or cursor");
        }
        var assets = request.getAssetIdsList();
        for (var asset : assets) {
            if (!store.ownsAssetOnPrimary(request.getTenantId(), positive(asset)))
                throw new IllegalArgumentException("Asset not found");
        }
        var cursor = request.getAfter();
        if (!request.getAfterEventId().isEmpty()) {
            var event = store.eventById(request.getTenantId(), request.getAfterEventId());
            cursor =
                    dev.fleet.common.rpc.EventCursor.newBuilder()
                            .setCreatedAtMicros(event.createdAtMicros())
                            .setId(event.id())
                            .build();
        }
        validateCursor(cursor);
        long lookBack = request.hasLookBackMicros() ? request.getLookBackMicros() : 5_000_000;
        if (lookBack < 0 || lookBack > 60_000_000) {
            throw new IllegalArgumentException("Look-back must be within 60 seconds");
        }
        var page = request.hasPageAfter() ? request.getPageAfter() : null;
        if (page != null) validateCursor(page);
        int limit = resultLimit(request.getLimit());
        var rows =
                store.eventsSince(
                        request.getTenantId(),
                        assets,
                        Math.max(0, cursor.getCreatedAtMicros() - lookBack),
                        page == null ? null : page.getCreatedAtMicros(),
                        page == null ? null : page.getId(),
                        limit);
        var result =
                dev.fleet.common.rpc.EventsSinceResult.newBuilder()
                        .setNext(page == null ? cursor : page);
        for (var row : rows) {
            result.addEvents(FleetProto.event(row));
            result.setNext(
                    dev.fleet.common.rpc.EventCursor.newBuilder()
                            .setCreatedAtMicros(row.createdAtMicros())
                            .setId(row.id()));
        }
        return result.build();
    }

    private void validateCursor(dev.fleet.common.rpc.EventCursor cursor) {
        if (cursor.getCreatedAtMicros() <= 0 || cursor.getId() <= 0) {
            throw new IllegalArgumentException("Invalid resume cursor");
        }
    }

    public List<NearbyVessel> findNearby(
            Long fenceId, Double lat, Double lon, double radiusM, long tenantId, int limit) {
        long tenant = tenantId;
        if (!Double.isFinite(radiusM) || radiusM <= 0 || radiusM > maxRadiusM) {
            throw new IllegalArgumentException("Radius exceeds allowed range");
        }
        if (fenceId != null) {
            if (lat != null || lon != null)
                throw new IllegalArgumentException("Supply fence or coordinates");
            var fence = store.fence(tenant, positive(fenceId));
            lat = fence.lat();
            lon = fence.lon();
        }
        if (lat == null
                || lon == null
                || !Double.isFinite(lat)
                || !Double.isFinite(lon)
                || Math.abs(lat) > 85.05112878
                || Math.abs(lon) > 180) {
            throw new IllegalArgumentException("Invalid center coordinates");
        }
        var points =
                redis.opsForGeo()
                        .search(
                                RedisKeys.geo(tenant),
                                GeoReference.fromCoordinate(lon, lat),
                                new Distance(radiusM, RedisGeoCommands.DistanceUnit.METERS),
                                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                                        .includeDistance()
                                        .sortAscending()
                                        .limit(limit));
        if (points == null) return List.of();
        return points.getContent().stream()
                .map(
                        point ->
                                new NearbyVessel(
                                        Long.parseLong(point.getContent().getName()),
                                        point.getDistance().getValue()))
                .toList();
    }

    public List<CopilotStore.Fence> listFences(long tenantId, int limit) {
        return store.fences(tenantId, limit);
    }

    public List<CopilotStore.NamedAsset> findAssets(long tenant, String name) {
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new IllegalArgumentException("Name must have 1 to 128 characters");
        }
        return store.findAssets(tenant, name.strip());
    }

    public CopilotStore.EventSummary aggregateEvents(dev.fleet.common.rpc.EventsRequest r) {
        // Reuse ownership and range validation without fetching the capped detail list.
        Long asset = r.hasAssetId() ? r.getAssetId() : null;
        Long fence = r.hasFenceId() ? r.getFenceId() : null;
        if (asset != null) requireAsset(r.getTenantId(), asset);
        if (fence != null && !store.ownsFence(r.getTenantId(), positive(fence))) {
            throw new IllegalArgumentException("Fence not found");
        }
        Long from = r.hasFromTs() ? r.getFromTs() : null;
        Long to = r.hasToTs() ? r.getToTs() : null;
        if (from == null || to == null || from < 0 || to < from || to - from > 366L * 86400000) {
            throw new IllegalArgumentException("Aggregate range must be within 366 days");
        }
        String type = r.hasEventType() ? r.getEventType() : null;
        if (type != null && !type.equals("ENTER") && !type.equals("EXIT")) {
            throw new IllegalArgumentException("Type must be ENTER or EXIT");
        }
        return store.aggregateEvents(r.getTenantId(), asset, fence, from, to, type);
    }

    public CopilotStore.FleetState fleetState(long tenant) {
        return store.fleetState(tenant);
    }

    public dev.fleet.common.rpc.EventQueryResult queryEvents(
            dev.fleet.common.rpc.EventQueryRequest request) {
        var spec = dev.fleet.common.EventQuerySpec.from(request);
        // Validate every explicit identifier before SQL; receipts use these same canonical IDs.
        var receipt = receipt("queryEvents", spec.request());
        return store.queryEvents(spec).toBuilder().setReceipt(receipt).build();
    }

    public dev.fleet.common.rpc.QueryReceipt receipt(
            String tool, com.google.protobuf.Message request) {
        long tenant =
                ((Number)
                                request.getField(
                                        request.getDescriptorForType()
                                                .findFieldByName("tenant_id")))
                        .longValue();
        var receipt = dev.fleet.common.rpc.QueryReceipt.newBuilder().setTool(tool);
        for (var entry : request.getAllFields().entrySet()) {
            var field = entry.getKey();
            if (field.getName().equals("tenant_id")) continue;
            String value =
                    field.isRepeated() ? Json.write(entry.getValue()) : entry.getValue().toString();
            receipt.putArguments(field.getJsonName(), value);
            boolean port = field.getName().equals("fence_id") || field.getName().equals("ports");
            boolean vessel =
                    field.getName().equals("asset_id")
                            || field.getName().equals("asset_ids")
                            || field.getName().equals("vessels");
            if (port || vessel) {
                var ids =
                        field.isRepeated() ? (List<?>) entry.getValue() : List.of(entry.getValue());
                for (Object id : ids) {
                    long number = positive(((Number) id).longValue());
                    var name =
                            dev.fleet.common.rpc.QueryName.newBuilder()
                                    .setId(number)
                                    .setName(store.resourceName(tenant, number, port));
                    if (port) receipt.addPorts(name);
                    else receipt.addVessels(name);
                }
            }
        }
        // proto3 omits zero scalar values from getAllFields; epoch zero is still an executed bound.
        if (request instanceof dev.fleet.common.rpc.EventQueryRequest query) {
            receipt.putArguments("fromTs", Long.toString(query.getFromTs()));
            receipt.putArguments("toTs", Long.toString(query.getToTs()));
            receipt.putArguments("ports", Json.write(query.getPortsList()));
            receipt.putArguments("vessels", Json.write(query.getVesselsList()));
        }
        return receipt.build();
    }

    private long positive(long id) {
        if (id <= 0) throw new IllegalArgumentException("ID must be positive");
        return id;
    }

    private void requireAsset(long tenant, long asset) {
        if (!store.ownsAsset(tenant, positive(asset)))
            throw new IllegalArgumentException("Asset not found");
    }
}
