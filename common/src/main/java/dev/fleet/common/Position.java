package dev.fleet.common;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Position(
        @JsonProperty(required = true) long tenantId,
        @JsonProperty(required = true) long assetId,
        @JsonProperty(required = true) long mmsi,
        @JsonProperty(required = true) long positionTs,
        @JsonProperty(required = true) double lat,
        @JsonProperty(required = true) double lon,
        Double sog,
        Double cog,
        Double heading,
        @JsonProperty(required = true) long emittedAt,
        long ingestedAt) {
    public Position {
        if (tenantId <= 0
                || assetId <= 0
                || tenantId > 9_007_199_254_740_991L
                || assetId > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("Invalid tenant or asset");
        }
        new IngestPoint(mmsi, positionTs, lat, lon, sog, cog, heading, emittedAt);
        if (ingestedAt < 0 || ingestedAt > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("Invalid server receipt timestamp");
        }
    }

    /** Legacy fixtures/payloads have no server timestamp; zero is excluded from latency samples. */
    public Position(
            long tenantId,
            long assetId,
            long mmsi,
            long positionTs,
            double lat,
            double lon,
            Double sog,
            Double cog,
            Double heading,
            long emittedAt) {
        this(tenantId, assetId, mmsi, positionTs, lat, lon, sog, cog, heading, emittedAt, 0);
    }

    public static Position of(long tenantId, long assetId, IngestPoint p) {
        return of(tenantId, assetId, p, 0);
    }

    public static Position of(long tenantId, long assetId, IngestPoint p, long ingestedAt) {
        return new Position(
                tenantId,
                assetId,
                p.mmsi(),
                p.positionTs(),
                p.lat(),
                p.lon(),
                p.sog(),
                p.cog(),
                p.heading(),
                p.emittedAt(),
                ingestedAt);
    }
}
