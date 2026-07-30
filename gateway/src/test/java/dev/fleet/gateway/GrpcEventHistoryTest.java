package dev.fleet.gateway;

import static org.assertj.core.api.Assertions.*;

import dev.fleet.common.rpc.*;

import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class GrpcEventHistoryTest {
    @Test
    void pagesUseOneDeadlineAndDetectOversizedGapBeforeReturningAnyHistory() throws Exception {
        var calls = new AtomicInteger();
        String name = InProcessServerBuilder.generateName();
        var service =
                new FleetDataGrpc.FleetDataImplBase() {
                    @Override
                    public void listEventsSince(
                            EventsSinceRequest request, StreamObserver<EventsSinceResult> reply) {
                        assertThat(request.getTenantId()).isEqualTo(1);
                        assertThat(request.getAssetIdsList()).containsExactly(366000000L);
                        assertThat(request.getLimit()).isBetween(1, 50);
                        int page = calls.getAndIncrement();
                        var result = EventsSinceResult.newBuilder();
                        for (int i = 0; i < request.getLimit(); i++) {
                            long ts = page * 50L + i + 1;
                            String id = String.format("%064x", ts);
                            result.addEvents(
                                    GeofenceEvent.newBuilder()
                                            .setEventId(id)
                                            .setAssetId(366000000L)
                                            .setFenceId(1)
                                            .setFenceVersion(1)
                                            .setType(GeofenceEvent.Type.ENTER)
                                            .setPositionTs(ts)
                                            .setCreatedAtMicros(ts * 1000)
                                            .setId(ts));
                            result.setNext(
                                    EventCursor.newBuilder()
                                            .setCreatedAtMicros(ts * 1000)
                                            .setId(ts));
                        }
                        reply.onNext(result.build());
                        reply.onCompleted();
                    }
                };
        var server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(service)
                        .build()
                        .start();
        var channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            var history = new GrpcEventHistory(channel, 51, Duration.ofSeconds(2));
            assertThatThrownBy(
                            () ->
                                    history.since(
                                            1,
                                            List.of(366000000L),
                                            false,
                                            new EventHistory.Cursor(1000, 1)))
                    .hasMessageContaining("exceeds event limit");
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    void unavailableHistoryHasABoundedDeadline() throws Exception {
        String name = InProcessServerBuilder.generateName();
        var server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(
                                new FleetDataGrpc.FleetDataImplBase() {
                                    @Override
                                    public void listEventsSince(
                                            EventsSinceRequest request,
                                            StreamObserver<EventsSinceResult> reply) {
                                        // Simulate an API that never replies.
                                    }
                                })
                        .build()
                        .start();
        var channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            var history = new GrpcEventHistory(channel, 1000, Duration.ofMillis(100));
            assertThatThrownBy(
                            () ->
                                    history.since(
                                            1, List.of(), true, new EventHistory.Cursor(1000, 1)))
                    .hasMessageContaining("DEADLINE_EXCEEDED");
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }
}
