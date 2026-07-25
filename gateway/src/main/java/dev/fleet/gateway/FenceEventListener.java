package dev.fleet.gateway;

import dev.fleet.common.*;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FenceEventListener {
    private final GatewayHub hub;

    public FenceEventListener(GatewayHub hub) {
        this.hub = hub;
    }

    @KafkaListener(
            id = "gateway-fence-events",
            topics = Topics.FENCE_EVENTS,
            groupId = "${gateway.event-group-id:fleet-gateway-${random.uuid}}")
    public void onEvent(String payload) {
        hub.event(Json.read(payload, GeofenceEvent.class));
    }
}
