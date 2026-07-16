package dev.fleet.processor;

import dev.fleet.processor.geofence.*;

import java.time.Duration;

final class TestGeofences {
    static GeofenceEngine empty() {
        return new GeofenceEngine(
                new FenceIndex(),
                new GeofenceSettings(
                        GeofenceSettings.Mode.HYSTERESIS, 3, Duration.ofSeconds(120)),
                assets -> java.util.List.of());
    }
}
