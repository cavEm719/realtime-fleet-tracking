package dev.fleet.processor.geofence;

public record ConfirmedState(
        long tenantId,
        long assetId,
        long fenceId,
        boolean inside,
        long lastTransitionPositionTs,
        long fenceVersion) {}
