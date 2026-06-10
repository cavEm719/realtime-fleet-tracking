package dev.fleet.common;

import com.fasterxml.jackson.annotation.JsonProperty;

public record IngestPoint(
        @JsonProperty(required = true) long mmsi,
        @JsonProperty(required = true) long positionTs,
        @JsonProperty(required = true) double lat,
        @JsonProperty(required = true) double lon,
        Double sog,
        Double cog,
        Double heading,
        @JsonProperty(required = true) long emittedAt) {
    public IngestPoint {
        if (mmsi < 100_000_000L || mmsi > 999_999_999L) {
            throw new IllegalArgumentException("MMSI must have nine digits");
        }
        if (positionTs < 0
                || positionTs > 9_007_199_254_740_991L
                || emittedAt < 0
                || emittedAt > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("Invalid millisecond timestamp");
        }
        if (!Double.isFinite(lat)
                || Math.abs(lat) > 85.05112878
                || !Double.isFinite(lon)
                || Math.abs(lon) > 180) {
            throw new IllegalArgumentException("Coordinates outside Redis GEO bounds");
        }
        if (sog != null && (!Double.isFinite(sog) || sog < 0)) {
            throw new IllegalArgumentException("Invalid SOG");
        }
        if (cog != null && (!Double.isFinite(cog) || cog < 0 || cog >= 360)) {
            throw new IllegalArgumentException("Invalid COG");
        }
        if (heading != null && (!Double.isFinite(heading) || heading < 0 || heading >= 360)) {
            throw new IllegalArgumentException("Invalid heading");
        }
    }
}
