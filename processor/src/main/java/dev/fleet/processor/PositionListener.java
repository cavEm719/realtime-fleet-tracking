package dev.fleet.processor;

import dev.fleet.common.FaultInjector;
import dev.fleet.common.Json;
import dev.fleet.common.Position;
import dev.fleet.common.TenantAssignment;
import dev.fleet.common.Topics;
import dev.fleet.processor.geofence.GeofenceEngine;
import dev.fleet.processor.geofence.PartitionPosition;
import dev.fleet.processor.geofence.TransitionSink;

import io.micrometer.core.instrument.MeterRegistry;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class PositionListener {
    private final RedisPositionStore positions;
    private final MeterRegistry metrics;
    private final GeofenceEngine geofences;
    private final TransitionSink transitions;
    private final FaultInjector faults;

    public PositionListener(
            RedisPositionStore positions,
            MeterRegistry metrics,
            GeofenceEngine geofences,
            TransitionSink transitions,
            FaultInjector faults) {
        this.positions = positions;
        this.metrics = metrics;
        this.geofences = geofences;
        this.transitions = transitions;
        this.faults = faults;
    }

    @KafkaListener(
            id = "position-listener",
            idIsGroup = false,
            topics = Topics.POSITIONS,
            autoStartup = "${spring.kafka.listener.auto-startup:true}")
    public void onBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        var fullBatch = new ArrayList<PartitionPosition>(records.size());
        for (int index = 0; index < records.size(); index++) {
            Position position;
            try {
                position = decode(records.get(index));
            } catch (IllegalArgumentException error) {
                // The error handler commits the prefix; apply it before identifying the poison
                // record.
                applyPositions(fullBatch);
                throw new BatchListenerFailedException("Invalid position record", error, index);
            }
            var record = records.get(index);
            fullBatch.add(
                    new PartitionPosition(
                            new TopicPartition(record.topic(), record.partition()), position));
        }
        applyPositions(fullBatch);
        ack.acknowledge();
        metrics.counter("processor.batches.acknowledged").increment();
    }

    private Position decode(ConsumerRecord<String, String> record) {
        var position = Json.read(record.value(), Position.class);
        if (position == null
                || !Long.toString(position.mmsi()).equals(record.key())
                || position.assetId() != position.mmsi()
                || TenantAssignment.forMmsi(position.mmsi()) != position.tenantId()) {
            throw new IllegalArgumentException("Kafka key or tenant assignment mismatch");
        }
        return position;
    }

    private void applyPositions(List<PartitionPosition> observations) {
        if (observations.isEmpty()) {
            return;
        }
        var fullBatch = observations.stream().map(PartitionPosition::position).toList();
        var accepted = positions.apply(fullBatch);
        // Redis dedupe gates Redis effects only; replay must still reach the geofence engine.
        try {
            var confirmed = geofences.processBatch(observations);
            faults.hit(FaultInjector.Point.BEFORE_TX);
            if (!transitions.persist(confirmed)) {
                geofences.dropPartitions(
                        observations.stream()
                                .map(PartitionPosition::partition)
                                .distinct()
                                .toList());
            }
            faults.hit(FaultInjector.Point.AFTER_TX_BEFORE_OFFSET_COMMIT);
        } catch (RuntimeException | Error error) {
            geofences.dropPartitions(
                    observations.stream().map(PartitionPosition::partition).distinct().toList());
            throw error;
        }
        long count = accepted.stream().filter(Boolean::booleanValue).count();
        metrics.counter("processor.redis.accepted").increment(count);
        metrics.counter("processor.redis.filtered").increment(fullBatch.size() - count);
        for (int index = 0; index < fullBatch.size(); index++) {
            if (accepted.get(index)) {
                long latency =
                        Math.max(0, System.currentTimeMillis() - fullBatch.get(index).emittedAt());
                metrics.timer("processor.position.latency").record(latency, TimeUnit.MILLISECONDS);
            }
        }
    }
}
