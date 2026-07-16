package dev.fleet.processor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.fleet.common.*;
import dev.fleet.processor.geofence.GeofenceEngine;
import dev.fleet.processor.geofence.PartitionPosition;
import dev.fleet.processor.geofence.TransitionSink;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;

class PositionListenerTest {
    private Position point() {
        return new Position(
                1,
                366000000,
                366000000,
                1000,
                40.66,
                -74.05,
                null,
                null,
                null,
                System.currentTimeMillis());
    }

    private ConsumerRecord<String, String> record(Position p) {
        return new ConsumerRecord<>(Topics.POSITIONS, 0, 0, Long.toString(p.mmsi()), Json.write(p));
    }

    @Test
    void commitsOnlyAfterTheCompleteRedisBatchAndAlsoCommitsDuplicates() {
        var store = mock(RedisPositionStore.class);
        var ack = mock(Acknowledgment.class);
        var engine = mock(GeofenceEngine.class);
        var listener =
                new PositionListener(
                        store,
                        new SimpleMeterRegistry(),
                        engine,
                        changes -> true,
                        dev.fleet.common.FaultInjector.disabled());
        var p = point();
        when(store.apply(List.of(p))).thenReturn(List.of(false));
        when(engine.processBatch(anyList())).thenReturn(List.of());
        listener.onBatch(List.of(record(p)), ack);
        var order = inOrder(store, engine, ack);
        order.verify(store).apply(List.of(p));
        order.verify(engine)
                .processBatch(
                        List.of(new PartitionPosition(new TopicPartition(Topics.POSITIONS, 0), p)));
        order.verify(ack).acknowledge();
    }

    @Test
    void doesNotCommitOnRedisFailure() {
        var store = mock(RedisPositionStore.class);
        var ack = mock(Acknowledgment.class);
        when(store.apply(anyList())).thenThrow(new IllegalStateException("Redis failed"));
        assertThatThrownBy(
                        () ->
                                new PositionListener(
                                                store,
                                                new SimpleMeterRegistry(),
                                                TestGeofences.empty(),
                                                changes -> true,
                                                dev.fleet.common.FaultInjector.disabled())
                                        .onBatch(List.of(record(point())), ack))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ack);
    }

    @Test
    void appliesTheValidPrefixBeforeReportingThePoisonRecordIndex() {
        var store = mock(RedisPositionStore.class);
        var ack = mock(Acknowledgment.class);
        var p = point();
        when(store.apply(List.of(p))).thenReturn(List.of(true));
        var malformed =
                new ConsumerRecord<String, String>(Topics.POSITIONS, 0, 1, "366000000", "bad-json");
        assertThatThrownBy(
                        () ->
                                new PositionListener(
                                                store,
                                                new SimpleMeterRegistry(),
                                                TestGeofences.empty(),
                                                changes -> true,
                                                dev.fleet.common.FaultInjector.disabled())
                                        .onBatch(List.of(record(p), malformed, record(p)), ack))
                .isInstanceOfSatisfying(
                        BatchListenerFailedException.class,
                        error -> {
                            assertThat(error.getIndex()).isEqualTo(1);
                            assertThat(error.getCause())
                                    .isInstanceOf(IllegalArgumentException.class);
                        });
        verify(store).apply(List.of(p));
        verifyNoInteractions(ack);
    }

    @Test
    void rejectsSpoofedTenantAndWrongKafkaKey() {
        var store = mock(RedisPositionStore.class);
        var ack = mock(Acknowledgment.class);
        var wrong =
                new ConsumerRecord<String, String>(
                        Topics.POSITIONS, 0, 0, "366000001", Json.write(point()));
        assertThatThrownBy(
                        () ->
                                new PositionListener(
                                                store,
                                                new SimpleMeterRegistry(),
                                                TestGeofences.empty(),
                                                changes -> true,
                                                dev.fleet.common.FaultInjector.disabled())
                                        .onBatch(List.of(wrong), ack))
                .isInstanceOf(BatchListenerFailedException.class);
        verifyNoInteractions(store, ack);
    }

    @Test
    void persistenceFailureDropsEveryPartitionInBatchBeforeRethrowing() {
        var store = mock(RedisPositionStore.class);
        var engine = mock(GeofenceEngine.class);
        var sink = mock(TransitionSink.class);
        var ack = mock(Acknowledgment.class);
        var first = point();
        var second =
                new Position(1, 366000003, 366000003, 1000, 40.66, -74.05, null, null, null, 1000);
        when(store.apply(List.of(first, second))).thenReturn(List.of(true, true));
        when(engine.processBatch(anyList())).thenReturn(List.of());
        when(sink.persist(anyList()))
                .thenThrow(new DataAccessResourceFailureException("MySQL failed"));
        var listener =
                new PositionListener(
                        store, new SimpleMeterRegistry(), engine, sink, FaultInjector.disabled());
        var records =
                List.of(
                        record(first),
                        new ConsumerRecord<>(
                                Topics.POSITIONS,
                                1,
                                0,
                                Long.toString(second.mmsi()),
                                Json.write(second)));
        assertThatThrownBy(() -> listener.onBatch(records, ack))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(engine)
                .dropPartitions(
                        List.of(
                                new TopicPartition(Topics.POSITIONS, 0),
                                new TopicPartition(Topics.POSITIONS, 1)));
        verifyNoInteractions(ack);
    }
}
