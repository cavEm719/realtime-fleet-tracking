package dev.fleet.gateway;

import dev.fleet.common.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;

@Configuration
public class PositionSubscriber {
    private static final Logger LOG = LoggerFactory.getLogger(PositionSubscriber.class);

    @Bean
    RedisMessageListenerContainer positionSubscription(
            RedisConnectionFactory connections, GatewayHub hub, ExecutorService gatewayExecutor) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.setTaskExecutor(gatewayExecutor);
        org.springframework.data.redis.connection.MessageListener listener =
                (message, pattern) -> {
                    try {
                        var position =
                                Json.read(
                                        new String(message.getBody(), StandardCharsets.UTF_8),
                                        Position.class);
                        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
                        if (position == null
                                || !RedisKeys.channel(position.tenantId()).equals(channel)) {
                            throw new IllegalArgumentException("Redis channel/tenant mismatch");
                        }
                        hub.position(position);
                    } catch (IllegalArgumentException error) {
                        LOG.warn("Rejected position broadcast: {}", error.getMessage());
                    }
                };
        // Hub serializes lifecycle changes with a ReentrantLock, including listener registration,
        // so a late unsubscribe cannot remove a newly active tenant's channel.
        hub.tenantSessions(
                new GatewayHub.TenantSessions() {
                    @Override
                    public void active(long tenant) {
                        container.addMessageListener(
                                listener, new ChannelTopic(RedisKeys.channel(tenant)));
                    }

                    @Override
                    public void inactive(long tenant) {
                        container.removeMessageListener(
                                listener, new ChannelTopic(RedisKeys.channel(tenant)));
                    }
                });
        return container;
    }
}
