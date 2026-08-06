package dev.fleet.loadtest;

import dev.fleet.common.Json;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class LagMonitor {
    static void deleteStoppedGroups(Admin admin) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (true) {
            var groups = admin.listConsumerGroups().all().get(10, TimeUnit.SECONDS);
            var ids = groups.stream().map(ConsumerGroupListing::groupId).toList();
            if (ids.isEmpty()) return;
            try {
                admin.deleteConsumerGroups(ids).all().get(10, TimeUnit.SECONDS);
                return;
            } catch (java.util.concurrent.ExecutionException error) {
                var cause = error.getCause();
                boolean retryable =
                        cause instanceof org.apache.kafka.common.errors.GroupNotEmptyException
                                || cause
                                        instanceof org.apache.kafka.common.errors.GroupIdNotFoundException;
                if (!retryable || System.nanoTime() >= deadline) throw error;
                System.out.println(
                        Json.write(
                                Map.of(
                                        "event", "waiting-for-group-expiry",
                                        "error", cause.toString())));
                // A previous request can delete some groups while others are still expiring.
                // Refresh the list before retrying so already deleted groups are not submitted again.
                Thread.sleep(1000);
            }
        }
    }

    static void run(String[] args) throws Exception {
        try (var admin =
                AdminClient.create(
                        Map.of("bootstrap.servers", args[1], "request.timeout.ms", "5000"))) {
            if (args[0].equals("reset-kafka")) {
                // This command is used only against the isolated benchmark broker with services
                // stopped.
                var topics = admin.listTopics().names().get(10, TimeUnit.SECONDS);
                if (!topics.isEmpty()) admin.deleteTopics(topics).all().get(10, TimeUnit.SECONDS);
                deleteStoppedGroups(admin);
                return;
            }
            String group = args[2];
            long end = System.nanoTime() + Duration.ofSeconds(Long.parseLong(args[3])).toNanos();
            var partitions = new HashMap<TopicPartition, OffsetSpec>();
            for (int i = 0; i < 12; i++)
                partitions.put(new TopicPartition("positions", i), OffsetSpec.latest());
            try (var out = Files.newBufferedWriter(Path.of(args[4]))) {
                while (System.nanoTime() < end) {
                    try {
                        var committed =
                                admin.listConsumerGroupOffsets(group)
                                        .partitionsToOffsetAndMetadata()
                                        .get(5, TimeUnit.SECONDS);
                        var latest = admin.listOffsets(partitions).all().get(5, TimeUnit.SECONDS);
                        long lag = 0;
                        var perPartition = new TreeMap<String, Long>();
                        for (var entry : latest.entrySet()) {
                            var offset = committed.get(entry.getKey());
                            long value =
                                    Math.max(
                                            0,
                                            entry.getValue().offset()
                                                    - (offset == null ? 0 : offset.offset()));
                            perPartition.put(entry.getKey().toString(), value);
                            lag += value;
                        }
                        var members = new TreeMap<String, Object>();
                        var description =
                                admin.describeConsumerGroups(List.of(group))
                                        .all()
                                        .get(5, TimeUnit.SECONDS)
                                        .get(group);
                        for (var member : description.members()) {
                            long memberLag =
                                    member.assignment().topicPartitions().stream()
                                            .mapToLong(
                                                    partition ->
                                                            perPartition.getOrDefault(
                                                                    partition.toString(), 0L))
                                            .sum();
                            members.put(
                                    member.clientId(),
                                    Map.of(
                                            "host",
                                            member.host(),
                                            "lag",
                                            memberLag,
                                            "partitions",
                                            member.assignment().topicPartitions().stream()
                                                    .map(Object::toString)
                                                    .sorted()
                                                    .toList()));
                        }
                        out.write(
                                Json.write(
                                                Map.of(
                                                        "timeMs",
                                                        System.currentTimeMillis(),
                                                        "lag",
                                                        lag,
                                                        "partitions",
                                                        perPartition,
                                                        "members",
                                                        members))
                                        + "\n");
                    } catch (Exception error) {
                        out.write(
                                Json.write(
                                                Map.of(
                                                        "timeMs",
                                                        System.currentTimeMillis(),
                                                        "error",
                                                        error.toString()))
                                        + "\n");
                    }
                    out.flush();
                    Thread.sleep(1000);
                }
            }
        }
    }
}
