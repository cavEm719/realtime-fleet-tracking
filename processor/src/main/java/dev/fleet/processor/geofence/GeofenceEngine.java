package dev.fleet.processor.geofence;

import dev.fleet.common.Position;

import org.apache.kafka.common.TopicPartition;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** A Kafka partition owns all its asset timestamps and fence states. */
public final class GeofenceEngine {

    private static final class AssetState {
        long lastTs = -1;
        final Map<Long, FenceState> fences = new HashMap<>();
    }

    private static final class FenceState {
        boolean inside;
        int candidatePoints;
        long candidateSince;
        long version;
    }

    private final Map<TopicPartition, Map<AssetRef, AssetState>> partitions =
            new ConcurrentHashMap<>();
    private final FenceIndex index;
    private final GeofenceSettings settings;
    private final ConfirmedStateLoader loader;

    public GeofenceEngine(
            FenceIndex index, GeofenceSettings settings, ConfirmedStateLoader loader) {
        this.index = index;
        this.settings = settings;
        this.loader = loader;
    }

    public List<Transition> processBatch(List<PartitionPosition> batch) {
        restoreUnseenAssets(batch);
        var transitions = new ArrayList<Transition>();
        for (var observation : batch) {
            var assets =
                    partitions.computeIfAbsent(observation.partition(), unused -> new HashMap<>());
            var position = observation.position();
            var state =
                    assets.computeIfAbsent(
                            new AssetRef(position.tenantId(), position.assetId()),
                            unused -> new AssetState());
            if (position.positionTs() <= state.lastTs) {
                continue;
            }
            processPosition(position, state, transitions);
            state.lastTs = position.positionTs();
        }
        return List.copyOf(transitions);
    }

    private void restoreUnseenAssets(List<PartitionPosition> batch) {
        var unseen = new LinkedHashSet<AssetRef>();
        for (var observation : batch) {
            var assets = partitions.get(observation.partition());
            var position = observation.position();
            var ref = new AssetRef(position.tenantId(), position.assetId());
            if (assets == null || !assets.containsKey(ref)) {
                unseen.add(ref);
            }
        }
        if (unseen.isEmpty()) {
            return;
        }
        // One query for every unseen asset in this decoded Kafka batch.
        var confirmed = loader.load(List.copyOf(unseen));
        var byAsset = new HashMap<AssetRef, List<ConfirmedState>>();
        for (var row : confirmed) {
            byAsset.computeIfAbsent(
                            new AssetRef(row.tenantId(), row.assetId()),
                            unused -> new ArrayList<>())
                    .add(row);
        }
        for (var observation : batch) {
            var position = observation.position();
            var ref = new AssetRef(position.tenantId(), position.assetId());
            var assets =
                    partitions.computeIfAbsent(observation.partition(), unused -> new HashMap<>());
            if (!assets.containsKey(ref)) {
                assets.put(ref, restoredState(byAsset.getOrDefault(ref, List.of())));
            }
        }
    }

    private AssetState restoredState(List<ConfirmedState> rows) {
        var asset = new AssetState();
        for (var row : rows) {
            asset.lastTs = Math.max(asset.lastTs, row.lastTransitionPositionTs());
            var fence = index.get(row.fenceId());
            if (fence != null && fence.tenantId() == row.tenantId()) {
                var state = new FenceState();
                state.inside = row.inside();
                state.version = row.fenceVersion();
                asset.fences.put(row.fenceId(), state);
            }
        }
        return asset;
    }

    public void dropPartitions(Collection<TopicPartition> revoked) {
        for (var partition : revoked) {
            partitions.remove(partition);
        }
    }

    private void processPosition(
            Position position, AssetState asset, List<Transition> transitions) {
        var fences = index.forTenant(position.tenantId());
        // Drop state for fences that were deleted or moved to another company.
        asset.fences.keySet().removeIf(id -> {
            var fence = index.get(id);
            return fence == null || fence.tenantId() != position.tenantId();
        });
        for (var fence : fences) {
            var state = asset.fences.computeIfAbsent(fence.id(), unused -> new FenceState());
            if (state.version != fence.version()) {
                state.candidatePoints = 0;
                state.version = fence.version();
            }
            evaluate(position, fence, state, transitions);
            if (!state.inside && state.candidatePoints == 0) {
                asset.fences.remove(fence.id());
            }
        }
    }

    private void evaluate(
            Position position, Fence fence, FenceState state, List<Transition> transitions) {
        double distance =
                GeoDistance.meters(
                        position.lat(), position.lon(), fence.centerLat(), fence.centerLon());
        boolean naive = settings.mode() == GeofenceSettings.Mode.NAIVE;
        boolean qualifies =
                state.inside
                        ? distance > (naive ? fence.enterRadiusM() : fence.exitRadiusM())
                        : distance <= fence.enterRadiusM();
        if (!qualifies) {
            state.candidatePoints = 0;
            return;
        }
        if (state.candidatePoints == 0) {
            state.candidateSince = position.positionTs();
        }
        state.candidatePoints = Math.min(settings.confirmationPoints(), state.candidatePoints + 1);
        if (naive
                || (state.candidatePoints >= settings.confirmationPoints()
                        && position.positionTs() - state.candidateSince
                                >= settings.confirmationTime().toMillis())) {
            var type = state.inside ? Transition.Type.EXIT : Transition.Type.ENTER;
            transitions.add(
                    new Transition(
                            position.tenantId(),
                            position.assetId(),
                            fence.id(),
                            fence.version(),
                            position.positionTs(),
                            type,
                            settings.mode(),
                            position.ingestedAt(),
                            position.emittedAt()));
            state.inside = !state.inside;
            state.candidatePoints = 0;
        }
    }
}
