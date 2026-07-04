package dev.fleet.processor.geofence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("geofence")
public record GeofenceSettings(
        @DefaultValue("hysteresis") Mode mode,
        @DefaultValue("3") int confirmationPoints,
        @DefaultValue("120s") Duration confirmationTime) {
    public enum Mode {
        NAIVE,
        HYSTERESIS
    }

    public GeofenceSettings {
        if (mode == null
                || confirmationPoints < 1
                || confirmationTime == null
                || confirmationTime.isNegative()) {
            throw new IllegalArgumentException("Invalid geofence settings");
        }
    }
}
