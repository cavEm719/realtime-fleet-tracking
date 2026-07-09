package dev.fleet.processor.geofence;

import dev.fleet.common.Position;

import org.apache.kafka.common.TopicPartition;

public record PartitionPosition(TopicPartition partition, Position position) {}
