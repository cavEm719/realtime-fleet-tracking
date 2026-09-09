package dev.fleet.api.internal;

import dev.fleet.api.copilot.CopilotStore;
import dev.fleet.common.Position;
import dev.fleet.common.rpc.*;

final class FleetProto {
    private FleetProto() {}

    static AssetStatus assetStatus(FleetQueries.AssetStatus row, int limit) {
        var result = AssetStatus.newBuilder().setAssetId(row.assetId()).setStateLimit(limit);
        if (row.position() != null) {
            result.setPosition(position(row.position()));
        }
        for (var state : row.confirmedStates()) {
            result.addConfirmedStates(
                    ConfirmedFenceState.newBuilder()
                            .setFenceId(state.fenceId())
                            .setName(state.name())
                            .setInside(state.inside())
                            .setLastTransitionPositionTs(state.lastTransitionPositionTs())
                            .setFenceVersion(state.fenceVersion()));
        }
        return result.build();
    }

    private static LatestPosition position(Position row) {
        var result =
                LatestPosition.newBuilder()
                        .setTenantId(row.tenantId())
                        .setAssetId(row.assetId())
                        .setMmsi(row.mmsi())
                        .setPositionTs(row.positionTs())
                        .setLat(row.lat())
                        .setLon(row.lon())
                        .setEmittedAt(row.emittedAt());
        if (row.ingestedAt() > 0) {
            result.setIngestedAt(row.ingestedAt());
        }
        if (row.sog() != null) {
            result.setSog(row.sog());
        }
        if (row.cog() != null) {
            result.setCog(row.cog());
        }
        if (row.heading() != null) {
            result.setHeading(row.heading());
        }
        return result.build();
    }

    static GeofenceEvent event(CopilotStore.Event row) {
        return GeofenceEvent.newBuilder()
                .setEventId(row.eventId())
                .setAssetId(row.assetId())
                .setFenceId(row.fenceId())
                .setType(GeofenceEvent.Type.valueOf(row.type().name()))
                .setPositionTs(row.positionTs())
                .setFenceVersion(row.fenceVersion())
                .setCreatedAtMicros(row.createdAtMicros())
                .setId(row.id())
                .build();
    }

    static Fence fence(CopilotStore.Fence row) {
        return Fence.newBuilder()
                .setId(row.id())
                .setName(row.name())
                .setCenterLat(row.lat())
                .setCenterLon(row.lon())
                .setEnterRadiusM(row.enterRadiusM())
                .setExitRadiusM(row.exitRadiusM())
                .setVersion(row.version())
                .build();
    }

    static NearbyVessel nearby(FleetQueries.NearbyVessel row) {
        return NearbyVessel.newBuilder()
                .setAssetId(row.assetId())
                .setDistanceM(row.distanceM())
                .build();
    }
}
