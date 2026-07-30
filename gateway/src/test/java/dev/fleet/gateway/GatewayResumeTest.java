package dev.fleet.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import dev.fleet.common.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

class GatewayResumeTest {
    private static final String BEFORE = "a".repeat(64);

    private record Frame(String type, GeofenceEvent event, String message) {}

    private record Socket(WebSocketSession raw, List<Frame> frames) {}

    private Socket socket() throws Exception {
        var raw = mock(WebSocketSession.class);
        var frames = new CopyOnWriteArrayList<Frame>();
        when(raw.getId()).thenReturn(UUID.randomUUID().toString());
        when(raw.getAttributes()).thenReturn(Map.of("tenantId", 1));
        doAnswer(
                        call -> {
                            frames.add(
                                    Json.read(
                                            ((TextMessage) call.getArgument(0)).getPayload(),
                                            Frame.class));
                            return null;
                        })
                .when(raw)
                .sendMessage(any());
        return new Socket(raw, frames);
    }

    private GeofenceEvent event(String id, long ts) {
        return new GeofenceEvent(
                id.repeat(64),
                1,
                366000000,
                1,
                1,
                ts,
                ts == 2000 ? GeofenceEvent.Type.EXIT : GeofenceEvent.Type.ENTER);
    }

    private List<GeofenceEvent> events(Socket socket) {
        return socket.frames().stream().filter(f -> f.event() != null).map(Frame::event).toList();
    }

    @Test
    void reconnectReplaysMissedEventsThenSwitchesToLiveWithoutReplayingCursor() throws Exception {
        try (var hub =
                new GatewayHub(
                        new GatewaySettings(100, 10000, 262144, 1024),
                        new SimpleMeterRegistry(),
                        Runnable::run)) {
            var old = socket();
            var client = hub.open(old.raw());
            hub.subscribe(client, List.of(), true);
            hub.event(event("a", 1000));
            client.terminate("closed");
            var fresh = socket();
            hub.history(
                    (tenant, assets, all, cursor) -> {
                        assertThat(tenant).isEqualTo(1);
                        assertThat(all).isTrue();
                        return List.of(event("a", 1000), event("b", 2000), event("c", 3000));
                    });
            hub.resume(
                    hub.open(fresh.raw()),
                    List.of(),
                    true,
                    new EventHistory.Cursor(1_000_000, 1),
                    List.of(BEFORE));
            hub.event(event("c", 3000));
            hub.event(event("a", 1000));
            hub.event(event("d", 4000));
            assertThat(events(fresh))
                    .containsExactly(event("b", 2000), event("c", 3000), event("d", 4000));
            assertThat(fresh.frames()).extracting(Frame::type).contains("resume_complete");
        }
    }

    @Test
    void liveDuringCatchupIsBufferedSortedAndDedupedAgainstHistory() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var hub =
                        new GatewayHub(
                                new GatewaySettings(100, 10000, 262144, 1024),
                                new SimpleMeterRegistry(),
                                workers)) {
            hub.history(
                    (tenant, assets, all, cursor) -> {
                        entered.countDown();
                        try {
                            if (!release.await(2, TimeUnit.SECONDS))
                                throw new IllegalStateException("timeout");
                        } catch (InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                        return List.of(event("b", 2000), event("c", 3000));
                    });
            var socket = socket();
            var client = hub.open(socket.raw());
            hub.resume(client, List.of(366000000L), false, new EventHistory.Cursor(1_000_000, 1));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            hub.event(event("d", 4000));
            hub.event(event("c", 3000));
            assertThat(events(socket)).isEmpty();
            assertThatThrownBy(() -> hub.unsubscribeAll(client))
                    .isInstanceOf(IllegalArgumentException.class);
            release.countDown();
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(
                            () ->
                                    assertThat(events(socket))
                                            .containsExactly(
                                                    event("b", 2000),
                                                    event("c", 3000),
                                                    event("d", 4000)));
            hub.event(event("d", 4000));
            assertThat(events(socket)).hasSize(3);
        } finally {
            release.countDown();
        }
    }

    @Test
    void earlierReportCommittedLaterIsDeliveredOnceAndOnlyConfirmedTenantIdsAreSeeded()
            throws Exception {
        try (var hub =
                new GatewayHub(
                        new GatewaySettings(100, 10000, 262144, 1024),
                        new SimpleMeterRegistry(),
                        Runnable::run)) {
            var b =
                    new GeofenceEvent(
                            "b".repeat(64),
                            1,
                            366000000,
                            1,
                            1,
                            9000,
                            GeofenceEvent.Type.ENTER,
                            0,
                            0,
                            2_000_000,
                            20);
            var old = socket();
            var disconnected = hub.open(old.raw());
            hub.subscribe(disconnected, List.of(), true);
            hub.event(b);
            disconnected.terminate("closed");
            // A commits after disconnect, although its report predates B's report.
            var a =
                    new GeofenceEvent(
                            "a".repeat(64),
                            1,
                            366000003,
                            1,
                            1,
                            1000,
                            GeofenceEvent.Type.ENTER,
                            0,
                            0,
                            3_000_000,
                            21);
            hub.history((tenant, assets, all, cursor) -> List.of(b, a));
            var fresh = socket();
            hub.resume(
                    hub.open(fresh.raw()),
                    List.of(),
                    true,
                    new EventHistory.Cursor(b.createdAtMicros(), b.id()),
                    List.of(b.eventId(), "f".repeat(64)));
            hub.event(a); // Relay redelivery overlaps the historical row.
            assertThat(events(fresh)).containsExactly(a);
            var foreignClaim =
                    new GeofenceEvent(
                            "f".repeat(64), 1, 366000006, 1, 1, 11000, GeofenceEvent.Type.EXIT);
            hub.event(foreignClaim); // Unknown ID was not honored merely because client listed it.
            assertThat(events(fresh)).containsExactly(a, foreignClaim);
            var clientDedupe = new HashSet<String>();
            old.frames().stream()
                    .filter(frame -> frame.event() != null)
                    .forEach(frame -> clientDedupe.add(frame.event().eventId()));
            assertThat(
                            events(fresh).stream()
                                    .filter(event -> clientDedupe.add(event.eventId()))
                                    .toList())
                    .containsExactly(a, foreignClaim);
        }
    }

    @Test
    void seenIdsAreBoundedAndMalformedIdsAreRejectedBeforeSubscription() throws Exception {
        try (var hub =
                new GatewayHub(
                        new GatewaySettings(100, 10000, 262144, 1024),
                        new SimpleMeterRegistry(),
                        Runnable::run)) {
            var client = hub.open(socket().raw());
            var cursor = new EventHistory.Cursor(1_000_000, 1);
            assertThatThrownBy(
                            () ->
                                    hub.resume(
                                            client,
                                            List.of(),
                                            true,
                                            cursor,
                                            Collections.nCopies(201, BEFORE)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () -> hub.resume(client, List.of(), true, cursor, List.of("invalid")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(client.all).isFalse();
        }
    }

    @Test
    void oversizedGapRequiresResyncWithoutPartialHistoryOrLaterLiveEvents() throws Exception {
        try (var hub =
                new GatewayHub(
                        new GatewaySettings(100, 10000, 262144, 1024, 1),
                        new SimpleMeterRegistry(),
                        Runnable::run)) {
            hub.history(
                    (tenant, assets, all, cursor) -> List.of(event("b", 2000), event("c", 3000)));
            var socket = socket();
            hub.resume(
                    hub.open(socket.raw()), List.of(), true, new EventHistory.Cursor(1_000_000, 1));
            hub.event(event("d", 4000));
            assertThat(events(socket)).isEmpty();
            assertThat(socket.frames())
                    .extracting(Frame::type)
                    .containsExactly("subscribed", "resync_required");
        }
    }
}
