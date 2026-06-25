package dev.fleet.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FenceRequest(
        String name,
        @JsonProperty(required = true) double centerLat,
        @JsonProperty(required = true) double centerLon,
        @JsonProperty(required = true) double enterRadiusM,
        @JsonProperty(required = true) double exitRadiusM) {
    public FenceRequest {
        if (name == null
                || name.isBlank()
                || name.length() > 128
                || !Double.isFinite(centerLat)
                || Math.abs(centerLat) > 85.05112878
                || !Double.isFinite(centerLon)
                || Math.abs(centerLon) > 180
                || !Double.isFinite(enterRadiusM)
                || !Double.isFinite(exitRadiusM)
                || enterRadiusM <= 0
                || exitRadiusM <= enterRadiusM
                || exitRadiusM >= Math.PI * 6_371_000 / 2) {
            throw new IllegalArgumentException("Invalid circular fence");
        }
    }
}
