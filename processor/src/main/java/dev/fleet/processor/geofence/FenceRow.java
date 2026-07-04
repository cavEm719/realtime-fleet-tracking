package dev.fleet.processor.geofence;

public record FenceRow(
        long id,
        long tenantId,
        String name,
        double centerLat,
        double centerLon,
        double enterRadiusM,
        double exitRadiusM,
        long version,
        boolean deleted) {
    public Fence fence() {
        return new Fence(
                id, tenantId, name, centerLat, centerLon, enterRadiusM, exitRadiusM, version);
    }
}
