package dev.fleet.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("gateway")
public record GatewaySettings(
        @DefaultValue("100") long flushIntervalMs,
        @DefaultValue("10000") int sendTimeLimitMs,
        @DefaultValue("262144") int bufferSizeBytes,
        @DefaultValue("1024") int eventDedupeSize,
        @DefaultValue("1000") int resumeMaxEvents,
        @DefaultValue("5000") long resumeLookBackMs) {
    public GatewaySettings(
            long flushIntervalMs, int sendTimeLimitMs, int bufferSizeBytes, int eventDedupeSize) {
        this(flushIntervalMs, sendTimeLimitMs, bufferSizeBytes, eventDedupeSize, 1000, 5000);
    }

    public GatewaySettings(
            long flushIntervalMs,
            int sendTimeLimitMs,
            int bufferSizeBytes,
            int eventDedupeSize,
            int resumeMaxEvents) {
        this(
                flushIntervalMs,
                sendTimeLimitMs,
                bufferSizeBytes,
                eventDedupeSize,
                resumeMaxEvents,
                5000);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public GatewaySettings {
        if (flushIntervalMs < 1
                || sendTimeLimitMs < 1
                || bufferSizeBytes < 1
                || eventDedupeSize < 1
                || resumeMaxEvents < 1
                || resumeLookBackMs < 0
                || resumeLookBackMs > 60000) {
            throw new IllegalArgumentException("Gateway limits must be positive");
        }
    }
}
