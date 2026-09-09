package dev.fleet.api.internal;

import dev.fleet.api.TenantConfigCache;
import dev.fleet.common.rpc.*;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class FleetDataService extends FleetDataGrpc.FleetDataImplBase {
    /** Status description the copilot maps to "data synchronization delayed". */
    public static final String REPLICA_DELAYED = "Replicated data is delayed";

    private final FleetQueries queries;
    private final TenantConfigCache tenants;
    private final AiReadDatabase ai;

    public FleetDataService(FleetQueries queries, TenantConfigCache tenants, AiReadDatabase ai) {
        this.queries = queries;
        this.tenants = tenants;
        this.ai = ai;
    }

    @Override
    public void getAssetStatus(AssetRequest request, StreamObserver<AssetStatus> reply) {
        read(
                request.getTenantId(),
                reply,
                Presentations::assetStatus,
                () -> {
                    int limit =
                            FleetQueries.resultLimit(
                                    request.hasStateLimit() ? request.getStateLimit() : null);
                    return FleetProto.assetStatus(
                                    queries.getAssetStatus(
                                            request.getAssetId(), request.getTenantId(), limit),
                                    limit)
                            .toBuilder()
                            .setReceipt(
                                    queries.receipt(
                                            "getAssetStatus",
                                            request.toBuilder().setStateLimit(limit).build()))
                            .build();
                });
    }

    @Override
    public void listEvents(EventsRequest r, StreamObserver<EventsResult> reply) {
        read(
                r.getTenantId(),
                reply,
                Presentations::events,
                () -> {
                    int limit = FleetQueries.resultLimit(r.hasLimit() ? r.getLimit() : null);
                    var events =
                            queries.listEvents(
                                    r.hasAssetId() ? r.getAssetId() : null,
                                    r.hasFenceId() ? r.getFenceId() : null,
                                    r.hasFromTs() ? r.getFromTs() : null,
                                    r.hasToTs() ? r.getToTs() : null,
                                    limit,
                                    r.getTenantId());
                    return EventsResult.newBuilder()
                            .setReceipt(
                                    queries.receipt(
                                            "listEvents",
                                            r.toBuilder().clearEventType().setLimit(limit).build()))
                            .setLimit(limit)
                            .addAllEvents(events.stream().map(FleetProto::event).toList())
                            .build();
                });
    }

    @Override
    public void listEventsSince(
            EventsSinceRequest request, StreamObserver<EventsSinceResult> reply) {
        execute(request.getTenantId(), reply, () -> queries.eventsSince(request));
    }

    @Override
    public void findNearby(NearbyRequest r, StreamObserver<NearbyResult> reply) {
        read(
                r.getTenantId(),
                reply,
                Presentations::nearby,
                () -> {
                    int limit = FleetQueries.resultLimit(r.hasLimit() ? r.getLimit() : null);
                    var nearby =
                            queries.findNearby(
                                    r.hasFenceId() ? r.getFenceId() : null,
                                    r.hasLat() ? r.getLat() : null,
                                    r.hasLon() ? r.getLon() : null,
                                    r.getRadiusM(),
                                    r.getTenantId(),
                                    limit);
                    return NearbyResult.newBuilder()
                            .setReceipt(
                                    queries.receipt(
                                            "findNearby", r.toBuilder().setLimit(limit).build()))
                            .setLimit(limit)
                            .addAllVessels(nearby.stream().map(FleetProto::nearby).toList())
                            .build();
                });
    }

    @Override
    public void listFences(FencesRequest r, StreamObserver<FencesResult> reply) {
        read(
                r.getTenantId(),
                reply,
                Presentations::fences,
                () -> {
                    int limit = FleetQueries.resultLimit(r.hasLimit() ? r.getLimit() : null);
                    return FencesResult.newBuilder()
                            .setReceipt(
                                    queries.receipt(
                                            "listFences", r.toBuilder().setLimit(limit).build()))
                            .setLimit(limit)
                            .addAllFences(
                                    queries.listFences(r.getTenantId(), limit).stream()
                                            .map(FleetProto::fence)
                                            .toList())
                            .build();
                });
    }

    @Override
    public void getTenantPolicy(TenantRequest r, StreamObserver<TenantPolicy> reply) {
        execute(
                r.getTenantId(),
                reply,
                () ->
                        TenantPolicy.newBuilder()
                                .setLlmRatePerMin(tenants.get(r.getTenantId()).llmRatePerMin())
                                .build());
    }

    @Override
    public void findAssets(AssetSearchRequest r, StreamObserver<AssetSearchResult> reply) {
        read(
                r.getTenantId(),
                reply,
                Presentations::assets,
                () -> {
                    var rows = queries.findAssets(r.getTenantId(), r.getName());
                    return AssetSearchResult.newBuilder()
                            .setReceipt(
                                    queries.receipt(
                                            "findAssets",
                                            r.toBuilder().setName(r.getName().strip()).build()))
                            .setTruncated(rows.size() > 50)
                            .addAllAssets(
                                    rows.stream()
                                            .limit(50)
                                            .map(
                                                    a ->
                                                            NamedAsset.newBuilder()
                                                                    .setAssetId(a.assetId())
                                                                    .setName(a.name())
                                                                    .build())
                                            .toList())
                            .build();
                });
    }

    @Override
    public void aggregateEvents(EventsRequest r, StreamObserver<EventSummary> reply) {
        read(
                r.getTenantId(),
                reply,
                Presentations::summary,
                () -> {
                    var summary = queries.aggregateEvents(r);
                    return EventSummary.newBuilder()
                            .setReceipt(
                                    queries.receipt(
                                            "aggregateEvents", r.toBuilder().clearLimit().build()))
                            .setTotalEvents(summary.totalEvents())
                            .setDistinctAssets(summary.distinctAssets())
                            .setTruncated(summary.truncated())
                            .addAllPorts(
                                    summary.ports().stream()
                                            .map(
                                                    p ->
                                                            PortEventCount.newBuilder()
                                                                    .setFenceId(p.fenceId())
                                                                    .setName(p.name())
                                                                    .setType(p.type())
                                                                    .setEvents(p.events())
                                                                    .setDistinctAssets(
                                                                            p.distinctAssets())
                                                                    .build())
                                            .toList())
                            .build();
                });
    }

    @Override
    public void getFleetState(FencesRequest r, StreamObserver<FleetStateSummary> reply) {
        read(
                r.getTenantId(),
                reply,
                Presentations::fleetState,
                () -> {
                    var summary = queries.fleetState(r.getTenantId());
                    return FleetStateSummary.newBuilder()
                            .setReceipt(
                                    queries.receipt(
                                            "getFleetState", r.toBuilder().clearLimit().build()))
                            .setInsideAssets(summary.insideAssets())
                            .setTruncated(summary.truncated())
                            .addAllPorts(
                                    summary.ports().stream()
                                            .map(
                                                    p ->
                                                            PortStateCount.newBuilder()
                                                                    .setFenceId(p.fenceId())
                                                                    .setName(p.name())
                                                                    .setInsideAssets(
                                                                            p.insideAssets())
                                                                    .build())
                                            .toList())
                            .addAllAssets(
                                    summary.assets().stream()
                                            .map(
                                                    a ->
                                                            InsideAsset.newBuilder()
                                                                    .setAssetId(a.assetId())
                                                                    .setFenceId(a.fenceId())
                                                                    .build())
                                            .toList())
                            .build();
                });
    }

    @Override
    public void queryEvents(EventQueryRequest request, StreamObserver<EventQueryResult> reply) {
        read(
                request.getTenantId(),
                reply,
                Presentations::query,
                () -> queries.queryEvents(request));
    }

    /**
     * Copilot reads: check replica freshness first, then attach the code-rendered presentation.
     */
    private <T extends com.google.protobuf.Message> void read(
            long tenant,
            StreamObserver<T> reply,
            java.util.function.BiFunction<T, java.time.Instant, Presentation> present,
            Supplier<T> action) {
        execute(
                tenant,
                reply,
                () -> {
                    var asOf = ai.dataAsOf();
                    T result = action.get();
                    var field = result.getDescriptorForType().findFieldByName("presentation");
                    @SuppressWarnings("unchecked")
                    T presented =
                            (T)
                                    result.toBuilder()
                                            .setField(field, present.apply(result, asOf))
                                            .build();
                    return presented;
                });
    }

    private <T> void execute(long tenant, StreamObserver<T> reply, Supplier<T> action) {
        try {
            tenants.get(tenant);
            reply.onNext(action.get());
            reply.onCompleted();
        } catch (AiReadDatabase.Stale error) {
            reply.onError(Status.UNAVAILABLE.withDescription(REPLICA_DELAYED).asRuntimeException());
        } catch (IllegalArgumentException
                | org.springframework.web.server.ResponseStatusException error) {
            reply.onError(
                    Status.INVALID_ARGUMENT
                            .withDescription("Invalid request or tenant-owned resource unavailable")
                            .asRuntimeException());
        } catch (RuntimeException error) {
            reply.onError(
                    Status.UNAVAILABLE
                            .withDescription("Data temporarily unavailable")
                            .asRuntimeException());
        }
    }
}
