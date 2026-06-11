package dev.fleet.common;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GeofenceEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("tenant_id") long tenantId,
        @JsonProperty("asset_id") long assetId,
        @JsonProperty("fence_id") long fenceId,
        @JsonProperty("fence_version") long fenceVersion,
        @JsonProperty("position_ts") long positionTs,
        Type type,
        long ingestedAt,
        long emittedAt,
        long createdAtMicros,
        long id) {
    public GeofenceEvent(
            String eventId,
            long tenantId,
            long assetId,
            long fenceId,
            long fenceVersion,
            long positionTs,
            Type type,
            long ingestedAt,
            long emittedAt) {
        this(
                eventId,
                tenantId,
                assetId,
                fenceId,
                fenceVersion,
                positionTs,
                type,
                ingestedAt,
                emittedAt,
                0,
                0);
    }

    public GeofenceEvent(
            String eventId,
            long tenantId,
            long assetId,
            long fenceId,
            long fenceVersion,
            long positionTs,
            Type type) {
        this(eventId, tenantId, assetId, fenceId, fenceVersion, positionTs, type, 0, 0);
    }

    public enum Type {
        ENTER,
        EXIT
    }
}
