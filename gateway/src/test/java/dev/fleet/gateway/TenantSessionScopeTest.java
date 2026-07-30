package dev.fleet.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.fleet.common.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.*;

class TenantSessionScopeTest {
    private WebSocketSession socket(long tenant) {
        var socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(UUID.randomUUID().toString());
        when(socket.getAttributes()).thenReturn(Map.of("tenantId", tenant));
        return socket;
    }

    @Test
    void onlyFirstAndLastSessionChangeChannelSubscription() {
        try (var hub =
                new GatewayHub(
                        new GatewaySettings(100, 10000, 262144, 1024),
                        new SimpleMeterRegistry(),
                        Runnable::run)) {
            var listener = mock(GatewayHub.TenantSessions.class);
            hub.tenantSessions(listener);
            var one = hub.open(socket(1));
            var two = hub.open(socket(1));
            var other = hub.open(socket(2));
            verify(listener).active(1);
            verify(listener).active(2);
            one.terminate("closed");
            verify(listener, never()).inactive(1);
            two.terminate("closed");
            verify(listener).inactive(1);
            assertThat(hub.hasTenant(1)).isFalse();
            assertThat(hub.hasTenant(2)).isTrue();
            other.terminate("closed");
            verify(listener).inactive(2);
        }
    }

    @Test
    void closingLastSessionCannotUnsubscribeANewlyOpenedSession() throws Exception {
        var removing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var subscribed = ConcurrentHashMap.<Long>newKeySet();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var hub =
                        new GatewayHub(
                                new GatewaySettings(100, 10000, 262144, 1024),
                                new SimpleMeterRegistry(),
                                workers)) {
            hub.tenantSessions(
                    new GatewayHub.TenantSessions() {
                        @Override
                        public void active(long tenant) {
                            subscribed.add(tenant);
                        }

                        @Override
                        public void inactive(long tenant) {
                            removing.countDown();
                            try {
                                if (!release.await(3, TimeUnit.SECONDS))
                                    throw new IllegalStateException("timeout");
                            } catch (InterruptedException e) {
                                throw new IllegalStateException(e);
                            }
                            subscribed.remove(tenant);
                        }
                    });
            var old = hub.open(socket(1));
            var closed = workers.submit(() -> old.terminate("closed"));
            assertThat(removing.await(2, TimeUnit.SECONDS)).isTrue();
            var started = new CountDownLatch(1);
            var fresh =
                    workers.submit(
                            () -> {
                                started.countDown();
                                return hub.open(socket(1));
                            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            closed.get(2, TimeUnit.SECONDS);
            var client = fresh.get(2, TimeUnit.SECONDS);
            assertThat(subscribed).containsExactly(1L);
            assertThat(hub.hasTenant(1)).isTrue();
            client.terminate("closed");
            assertThat(subscribed).isEmpty();
        } finally {
            release.countDown();
        }
    }

    @Test
    void eventsForAbsentTenantsAreDroppedBeforeRegistryDelivery() {
        try (var hub =
                new GatewayHub(
                        new GatewaySettings(100, 10000, 262144, 1024),
                        new SimpleMeterRegistry(),
                        Runnable::run)) {
            var registry = mock(SubscriptionRegistry.class);
            org.springframework.test.util.ReflectionTestUtils.setField(hub, "registry", registry);
            hub.open(socket(1));
            hub.event(
                    new GeofenceEvent(
                            "a".repeat(64), 2, 366000001, 2, 1, 1000, GeofenceEvent.Type.ENTER));
            verifyNoInteractions(registry);
        }
    }
}
