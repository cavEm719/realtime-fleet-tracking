package dev.fleet.gateway;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.*;

import java.util.concurrent.*;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(GatewaySettings.class)
public class GatewayConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    io.grpc.ManagedChannel eventHistoryChannel(
            @org.springframework.beans.factory.annotation.Value(
                            "${internal.api-target:localhost:9095}")
                    String target) {
        return io.grpc.ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .disableRetry()
                .build();
    }

    @Bean
    EventHistory eventHistory(
            io.grpc.ManagedChannel eventHistoryChannel,
            GatewaySettings settings,
            @org.springframework.beans.factory.annotation.Value("${gateway.resume-timeout:5s}")
                    java.time.Duration timeout) {
        return new GrpcEventHistory(
                eventHistoryChannel,
                settings.resumeMaxEvents(),
                timeout,
                java.time.Duration.ofMillis(settings.resumeLookBackMs()));
    }

    @Bean(destroyMethod = "close")
    ExecutorService gatewayExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(destroyMethod = "close")
    GatewayHub gatewayHub(
            GatewaySettings settings,
            MeterRegistry metrics,
            ExecutorService gatewayExecutor,
            EventHistory eventHistory) {
        var hub = new GatewayHub(settings, metrics, gatewayExecutor);
        hub.history(eventHistory);
        return hub;
    }

    @Bean
    GatewaySocketHandler gatewaySocketHandler(GatewayHub hub) {
        return new GatewaySocketHandler(hub);
    }

    @Configuration
    @EnableWebSocket
    static class Sockets implements WebSocketConfigurer {
        private final GatewaySocketHandler handler;

        Sockets(GatewaySocketHandler handler) {
            this.handler = handler;
        }

        @Override
        public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(handler, "/ws").addInterceptors(new TenantHandshake());
        }
    }
}
