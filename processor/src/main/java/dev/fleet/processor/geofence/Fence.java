package dev.fleet.processor.geofence;

public record Fence(
        long id,
        long tenantId,
        String name,
        double centerLat,
        double centerLon,
        double enterRadiusM,
        double exitRadiusM,
        long version) {
    public Fence {
        if (id <= 0
                || tenantId <= 0
                || name == null
                || name.isBlank()
                || version <= 0
                || !Double.isFinite(centerLat)
                || Math.abs(centerLat) > 85.05112878
                || !Double.isFinite(centerLon)
                || Math.abs(centerLon) > 180
                || !Double.isFinite(enterRadiusM)
                || !Double.isFinite(exitRadiusM)
                || enterRadiusM <= 0
                || exitRadiusM <= enterRadiusM
                || exitRadiusM >= Math.PI * GeoDistance.EARTH_RADIUS_M / 2) {
            throw new IllegalArgumentException("Invalid circular fence");
        }
    }
}
