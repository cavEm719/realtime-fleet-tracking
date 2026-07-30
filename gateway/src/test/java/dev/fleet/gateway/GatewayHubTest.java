package dev.fleet.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import dev.fleet.common.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

class GatewayHubTest {
    private static final GatewaySettings DEFAULTS = new GatewaySettings(100, 10_000, 262144, 1024);

    private record Socket(WebSocketSession raw, List<String> frames, AtomicBoolean open) {}

    private Socket socket(long tenant) throws Exception {
        var raw = mock(WebSocketSession.class);
        var frames = new CopyOnWriteArrayList<String>();
        var open = new AtomicBoolean(true);
        when(raw.getId()).thenReturn(UUID.randomUUID().toString());
        when(raw.getAttributes()).thenReturn(Map.of("tenantId", tenant, "userId", "test"));
        when(raw.isOpen()).thenAnswer(invocation -> open.get());
        doAnswer(
                        invocation -> {
                            frames.add(((TextMessage) invocation.getArgument(0)).getPayload());
                            return null;
                        })
                .when(raw)
                .sendMessage(any());
        doAnswer(
                        invocation -> {
                            open.set(false);
                            return null;
                        })
                .when(raw)
                .close(any(CloseStatus.class));
        return new Socket(raw, frames, open);
    }

    private Position point(long asset, long ts) {
        return new Position(
                TenantAssignment.forMmsi(asset),
                asset,
                asset,
                ts,
                33.73,
                -118.2625,
                null,
                null,
                null,
                System.currentTimeMillis());
    }

    private GeofenceEvent event(String id, long ts, GeofenceEvent.Type type) {
        return new GeofenceEvent(id, 1, 366000000, 1, 1, ts, type);
    }

    private record PositionsFrame(String type, List<Position> positions) {}

    private record EventFrame(String type, GeofenceEvent event) {}


    @Test
    void repeatedPositionUpdatesBecomeOneLatestEntryInOneFrame() throws Exception {
        var metrics = new SimpleMeterRegistry();
        try (var hub = new GatewayHub(DEFAULTS, metrics, Runnable::run)) {
            var socket = socket(1);
            var client = hub.open(socket.raw());
            hub.subscribe(client, List.of(366000000L), false);
            for (int i = 1; i <= 100; i++) {
                hub.position(point(366000000, i));
            }
            assertThat(socket.frames()).isEmpty();
            hub.flush();
            assertThat(socket.frames()).hasSize(1);
            assertThat(Json.read(socket.frames().getFirst(), PositionsFrame.class).positions())
                    .singleElement()
                    .extracting(Position::positionTs)
                    .isEqualTo(100L);
            hub.flush();
            assertThat(socket.frames()).hasSize(1);
            assertThat(metrics.get("gateway.positions.coalesced").counter().count()).isEqualTo(99);
            assertThat(metrics.get("gateway.delivery.latency").timer().count()).isEqualTo(1);
        }
    }

    @Test
    void tenantWideRecipientsShareTheSameSerializedMessageAndKeepAssetFiltering() throws Exception {
        try (var hub = new GatewayHub(DEFAULTS, new SimpleMeterRegistry(), Runnable::run)) {
            var first = socket(1);
            var second = socket(1);
            var assetOnly = socket(1);
            var otherTenant = socket(2);
            hub.subscribe(hub.open(first.raw()), List.of(366000000L), true);
            hub.subscribe(hub.open(second.raw()), List.of(), true);
            hub.subscribe(hub.open(assetOnly.raw()), List.of(366000000L), false);
            hub.subscribe(hub.open(otherTenant.raw()), List.of(), true);
            hub.position(point(366000000, 1));
            hub.position(point(366000003, 1));
            hub.flush();
            var firstMessage = org.mockito.ArgumentCaptor.forClass(WebSocketMessage.class);
            var secondMessage = org.mockito.ArgumentCaptor.forClass(WebSocketMessage.class);
            verify(first.raw()).sendMessage(firstMessage.capture());
            verify(second.raw()).sendMessage(secondMessage.capture());
            assertThat(firstMessage.getValue()).isSameAs(secondMessage.getValue());
            assertThat(first.frames()).hasSize(1);
            assertThat(Json.read(first.frames().getFirst(), PositionsFrame.class).positions())
                    .hasSize(2);
            assertThat(Json.read(assetOnly.frames().getFirst(), PositionsFrame.class).positions())
                    .singleElement()
                    .extracting(Position::assetId)
                    .isEqualTo(366000000L);
            assertThat(otherTenant.frames()).isEmpty();
        }
    }

    @Test
    void sharedPositionValuesKeepEachRecipientsOrderingGuard() throws Exception {
        try (var hub = new GatewayHub(DEFAULTS, new SimpleMeterRegistry(), Runnable::run)) {
            var first = socket(1);
            var second = socket(1);
            hub.subscribe(hub.open(first.raw()), List.of(), true);
            hub.subscribe(hub.open(second.raw()), List.of(366000000L), false);
            hub.position(point(366000000L, 100));
            hub.flush();
            hub.position(point(366000000L, 99));
            hub.position(point(366000000L, 100));
            hub.flush();
            hub.position(point(366000000L, 101));
            hub.flush();
            for (var receiver : List.of(first, second)) {
                assertThat(receiver.frames()).hasSize(2);
                assertThat(Json.read(receiver.frames().getLast(), PositionsFrame.class).positions())
                        .singleElement()
                        .extracting(Position::positionTs)
                        .isEqualTo(101L);
            }
        }
    }

    @Test
    void eventsAreImmediateDistinctAndNotPartOfPositionCoalescing() throws Exception {
        try (var hub = new GatewayHub(DEFAULTS, new SimpleMeterRegistry(), Runnable::run)) {
            var socket = socket(1);
            var client = hub.open(socket.raw());
            hub.subscribe(client, List.of(), true);
            hub.position(point(366000000, 1000));
            hub.event(event("enter", 1000, GeofenceEvent.Type.ENTER));
            hub.event(event("exit", 2000, GeofenceEvent.Type.EXIT));
            assertThat(socket.frames())
                    .hasSize(2)
                    .allSatisfy(frame -> assertThat(frame).contains("fence-event"));
            hub.flush();
            assertThat(socket.frames()).hasSize(3);
            assertThat(socket.frames().getLast()).contains("positions");
        }
    }

    @Test
    void dedupeAndTimestampGuardDropDuplicateOlderAndEqualEvents() throws Exception {
        try (var hub = new GatewayHub(DEFAULTS, new SimpleMeterRegistry(), Runnable::run)) {
            var socket = socket(1);
            hub.subscribe(hub.open(socket.raw()), List.of(), true);
            hub.event(event("newer-exit", 3000, GeofenceEvent.Type.EXIT));
            hub.event(event("newer-exit", 3000, GeofenceEvent.Type.EXIT));
            hub.event(event("old-enter", 2000, GeofenceEvent.Type.ENTER));
            hub.event(event("equal-enter", 3000, GeofenceEvent.Type.ENTER));
            hub.event(event("latest-enter", 4000, GeofenceEvent.Type.ENTER));
            hub.event(event("old-exit", 3500, GeofenceEvent.Type.EXIT));
            assertThat(
                            socket.frames().stream()
                                    .map(
                                            frame ->
                                                    Json.read(frame, EventFrame.class)
                                                            .event()
                                                            .eventId()))
                    .containsExactly("newer-exit", "latest-enter");
        }
    }

    @Test
    void crossTenantRequestIsRejectedBeforeAnySubscriptionIsAdded() throws Exception {
        try (var hub = new GatewayHub(DEFAULTS, new SimpleMeterRegistry(), Runnable::run)) {
            var socket = socket(1);
            var client = hub.open(socket.raw());
            assertThatThrownBy(() -> hub.subscribe(client, List.of(366000000L, 366000001L), false))
                    .isInstanceOf(IllegalArgumentException.class);
            hub.position(point(366000000, 1));
            hub.position(point(366000001, 1));
            hub.flush();
            assertThat(socket.frames()).isEmpty();
        }
    }

    @Test
    void allAndAssetSubscriptionsDoNotDoubleDeliverAndStayTenantScoped() throws Exception {
        try (var hub = new GatewayHub(DEFAULTS, new SimpleMeterRegistry(), Runnable::run)) {
            var first = socket(1);
            var second = socket(2);
            var client = hub.open(first.raw());
            hub.subscribe(client, List.of(366000000L), true);
            hub.subscribe(hub.open(second.raw()), List.of(), true);
            hub.event(event("once", 1000, GeofenceEvent.Type.ENTER));
            assertThat(first.frames()).hasSize(1);
            assertThat(second.frames()).isEmpty();
            hub.unsubscribe(client, List.of(366000000L), true);
            hub.event(event("not-subscribed", 2000, GeofenceEvent.Type.EXIT));
            assertThat(first.frames()).hasSize(1);
        }
    }

    @Test
    void boundedEventLruEvictsButTimestampGuardStillRejectsOldReplay() throws Exception {
        try (var hub =
                new GatewayHub(
                        new GatewaySettings(100, 10_000, 262144, 2),
                        new SimpleMeterRegistry(),
                        Runnable::run)) {
            var socket = socket(1);
            var client = hub.open(socket.raw());
            hub.subscribe(client, List.of(), true);
            hub.event(event("a", 1000, GeofenceEvent.Type.ENTER));
            hub.event(event("b", 2000, GeofenceEvent.Type.EXIT));
            hub.event(event("c", 3000, GeofenceEvent.Type.ENTER));
            hub.event(event("a", 1000, GeofenceEvent.Type.ENTER));
            assertThat(socket.frames()).hasSize(3);
            assertThat((Map<?, ?>) ReflectionTestUtils.getField(client, "eventIds")).hasSize(2);
        }
    }

    @Test
    void blockedClientOverflowsWhileNormalClientKeepsReceiving() throws Exception {
        var metrics = new SimpleMeterRegistry();
        var slow = socket(1);
        var normal = socket(1);
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            blocked.countDown();
                            release.await(10, TimeUnit.SECONDS);
                            return null;
                        })
                .when(slow.raw())
                .sendMessage(any());
        doAnswer(
                        invocation -> {
                            slow.open().set(false);
                            release.countDown();
                            return null;
                        })
                .when(slow.raw())
                .close(any(CloseStatus.class));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var hub =
                        new GatewayHub(
                                new GatewaySettings(100, 10_000, 1024, 1024), metrics, executor)) {
            hub.subscribe(hub.open(slow.raw()), List.of(), true);
            hub.subscribe(hub.open(normal.raw()), List.of(), true);
            hub.event(event("initial", 1000, GeofenceEvent.Type.ENTER));
            assertThat(blocked.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 2; i <= 12; i++) {
                hub.event(event("event-" + i, i * 1000L, GeofenceEvent.Type.EXIT));
                int expected = i;
                await().atMost(Duration.ofSeconds(2))
                        .until(() -> normal.frames().size() == expected);
            }
            await().atMost(Duration.ofSeconds(2)).until(() -> !slow.open().get());
            assertThat(normal.open()).isTrue();
            assertThat(
                            metrics.get("gateway.sessions.terminated")
                                    .tag("reason", "overflow")
                                    .counter()
                                    .count())
                    .isEqualTo(1);
            assertThat(metrics.get("gateway.sessions.open").gauge().value()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void blockedTransportCloseDoesNotBlockDeliveryToOtherClients() throws Exception {
        var slow = socket(1);
        var normal = socket(1);
        var sendStarted = new CountDownLatch(1);
        var closeStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            sendStarted.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return null;
                        })
                .when(slow.raw())
                .sendMessage(any());
        doAnswer(
                        invocation -> {
                            closeStarted.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            slow.open().set(false);
                            return null;
                        })
                .when(slow.raw())
                .close(any(CloseStatus.class));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var hub = new GatewayHub(DEFAULTS, new SimpleMeterRegistry(), executor)) {
            try {
                var blockedClient = hub.open(slow.raw());
                hub.subscribe(blockedClient, List.of(), true);
                hub.subscribe(hub.open(normal.raw()), List.of(), true);
                hub.event(event("first", 1000, GeofenceEvent.Type.ENTER));
                assertThat(sendStarted.await(2, TimeUnit.SECONDS)).isTrue();
                blockedClient.terminate("overflow");
                assertThat(closeStarted.await(2, TimeUnit.SECONDS)).isTrue();
                hub.event(event("second", 2000, GeofenceEvent.Type.EXIT));
                await().atMost(Duration.ofSeconds(2)).until(() -> normal.frames().size() == 2);
                assertThat(blockedClient.isClosed()).isTrue();
                assertThat(slow.open()).isTrue();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void activeSendTimeLimitIsCheckedWithoutNewTraffic() throws Exception {
        var metrics = new SimpleMeterRegistry();
        var slow = socket(1);
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            blocked.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return null;
                        })
                .when(slow.raw())
                .sendMessage(any());
        doAnswer(
                        invocation -> {
                            slow.open().set(false);
                            release.countDown();
                            return null;
                        })
                .when(slow.raw())
                .close(any(CloseStatus.class));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var hub =
                        new GatewayHub(
                                new GatewaySettings(100, 25, 262144, 1024), metrics, executor)) {
            hub.subscribe(hub.open(slow.raw()), List.of(), true);
            hub.event(event("blocked", 1000, GeofenceEvent.Type.ENTER));
            assertThat(blocked.await(2, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () -> {
                                hub.flush();
                                assertThat(slow.open()).isFalse();
                            });
            assertThat(
                            metrics.get("gateway.sessions.terminated")
                                    .tag("reason", "send_time")
                                    .counter()
                                    .count())
                    .isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void copyOnWriteUpdatesNeverLoseOtherAssetOrTenantSubscriptions() throws Exception {
        try (var hub = new GatewayHub(DEFAULTS, new SimpleMeterRegistry(), Runnable::run);
                var workers = Executors.newFixedThreadPool(8)) {
            var sockets = new ArrayList<Socket>();
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 32; i++) {
                var socket = socket(1);
                sockets.add(socket);
                var client = hub.open(socket.raw());
                tasks.add(
                        workers.submit(
                                () -> {
                                    for (int n = 0; n < 50; n++) {
                                        hub.subscribe(client, List.of(366000000L), true);
                                        hub.position(point(366000000L, n));
                                        hub.unsubscribe(client, List.of(366000000L), true);
                                    }
                                    hub.subscribe(client, List.of(366000000L), true);
                                }));
            }
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
            hub.position(point(366000000L, 100_000));
            hub.flush();
            for (var socket : sockets) {
                assertThat(socket.frames()).hasSize(1);
                assertThat(Json.read(socket.frames().getFirst(), PositionsFrame.class).positions())
                        .singleElement()
                        .extracting(Position::positionTs)
                        .isEqualTo(100_000L);
            }
        }
    }

    @Test
    void concurrentRegistryOperationsPreserveFinalSubscriptionsAcrossManyAssets() throws Exception {
        try (var hub = new GatewayHub(DEFAULTS, new SimpleMeterRegistry(), Runnable::run);
                var workers = Executors.newFixedThreadPool(8)) {
            var sockets = new ArrayList<Socket>();
            var clients = new ArrayList<ClientSession>();
            for (int i = 0; i < 64; i++) {
                var socket = socket(1);
                sockets.add(socket);
                clients.add(hub.open(socket.raw()));
            }
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 64; i++) {
                var client = clients.get(i);
                long asset = 366000000L + i * 3L;
                tasks.add(
                        workers.submit(
                                () -> {
                                    for (int n = 0; n < 40; n++) {
                                        hub.subscribe(client, List.of(asset), false);
                                        hub.position(point(asset, n));
                                        hub.unsubscribe(client, List.of(asset), false);
                                    }
                                    hub.subscribe(client, List.of(asset), false);
                                }));
            }
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
            for (int i = 0; i < 64; i++) {
                hub.position(point(366000000L + i * 3L, 100_000));
            }
            hub.flush();
            for (var socket : sockets) {
                assertThat(socket.frames()).hasSize(1);
                assertThat(Json.read(socket.frames().getFirst(), PositionsFrame.class).positions())
                        .singleElement()
                        .extracting(Position::positionTs)
                        .isEqualTo(100_000L);
            }
        }
    }
}
