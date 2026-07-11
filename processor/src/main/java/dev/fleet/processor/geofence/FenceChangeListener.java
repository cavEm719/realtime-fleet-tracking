package dev.fleet.processor.geofence;

import dev.fleet.common.FenceChange;
import dev.fleet.common.Json;
import dev.fleet.common.Topics;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FenceChangeListener {
    private final FenceCache cache;

    public FenceChangeListener(FenceCache cache) {
        this.cache = cache;
    }

    @KafkaListener(
            id = "fence-change-listener",
            topics = Topics.FENCE_CHANGES,
            groupId = "${fence-cache.group-id:fleet-fence-cache-${random.uuid}}",
            containerFactory = "fenceChangeFactory",
            autoStartup = "${spring.kafka.listener.auto-startup:true}")
    public void onChange(String payload) {
        cache.changed(Json.read(payload, FenceChange.class));
    }
}
