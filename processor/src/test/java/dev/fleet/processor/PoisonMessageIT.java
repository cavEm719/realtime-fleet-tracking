package dev.fleet.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.fleet.common.Json;
import dev.fleet.common.Position;
import dev.fleet.common.RedisKeys;
import dev.fleet.common.Topics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.BatchAcknowledgingMessageListener;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Testcontainers
class PoisonMessageIT {
    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.9")).withExposedPorts(6379);

    private static Admin admin;
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private static DefaultKafkaProducerFactory<String, String> producers;
    private static KafkaTemplate<String, String> kafka;

    @BeforeAll
    static void startClients() throws Exception {
        admin =
                Admin.create(
                        Map.of(
                                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers()));
        admin.createTopics(
                        List.of(
                                new NewTopic(
                                        Topics.POSITIONS, Topics.POSITION_PARTITIONS, (short) 1),
                                new NewTopic(
                                        Topics.POSITIONS_DLT,
                                        Topics.POSITION_PARTITIONS,
                                        (short) 1)))
                .all()
                .get(20, TimeUnit.SECONDS);
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet();
        connection.start();
        redis = new StringRedisTemplate(connection);
        producers =
                new DefaultKafkaProducerFactory<>(
                        Map.of(
                                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers(),
                                ProducerConfig.ACKS_CONFIG,
                                "all"),
                        new StringSerializer(),
                        new StringSerializer());
        kafka = new KafkaTemplate<>(producers);
    }

    @AfterAll
    static void closeClients() {
        producers.destroy();
        connection.destroy();
        admin.close();
    }

    @BeforeEach
    void clearData() throws Exception {
        try (var redisConnection = connection.getConnection()) {
            redisConnection.serverCommands().flushDb();
        }
        var partitions = new HashMap<TopicPartition, OffsetSpec>();
        for (String topic : List.of(Topics.POSITIONS, Topics.POSITIONS_DLT)) {
            for (int partition = 0; partition < Topics.POSITION_PARTITIONS; partition++) {
                partitions.put(new TopicPartition(topic, partition), OffsetSpec.latest());
            }
        }
        var offsets = admin.listOffsets(partitions).all().get(10, TimeUnit.SECONDS);
        var deletions = new HashMap<TopicPartition, RecordsToDelete>();
        offsets.forEach(
                (partition, offset) -> {
                    if (offset.offset() > 0) {
                        deletions.put(partition, RecordsToDelete.beforeOffset(offset.offset()));
                    }
                });
        if (!deletions.isEmpty()) {
            admin.deleteRecords(deletions).all().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void malformedMiddleRecordGoesToDltWhileBothValidRecordsAreAppliedAndCommitted()
            throws Exception {
        var first = point(366000000, 1000);
        var last = point(366000003, 2000);
        send(first);
        kafka.send(new ProducerRecord<>(Topics.POSITIONS, 0, "366000099", "malformed-json"))
                .get(10, TimeUnit.SECONDS);
        long finalOffset = send(last);
        String group = "poison-test-" + UUID.randomUUID();
        var sawMiddleFailure = new AtomicBoolean();
        var listener =
                new PositionListener(
                        new RedisPositionStore(redis),
                        new SimpleMeterRegistry(),
                        TestGeofences.empty(),
                        changes -> true,
                        dev.fleet.common.FaultInjector.disabled());
        var container = container(group);
        container.setupMessageListener(
                (BatchAcknowledgingMessageListener<String, String>)
                        (records, ack) -> {
                            if (records.size() == 3
                                    && "malformed-json".equals(records.get(1).value())) {
                                sawMiddleFailure.set(true);
                            }
                            listener.onBatch(records, ack);
                        });
        try (var dlt = dltConsumer()) {
            container.start();
            awaitCommitted(group, finalOffset + 1);
            assertThat(sawMiddleFailure).isTrue();
            assertThat(latest(first)).isEqualTo(first);
            assertThat(latest(last)).isEqualTo(last);
            var deadLetters = dlt.poll(Duration.ofSeconds(10));
            assertThat(deadLetters.count()).isEqualTo(1);
            var record = deadLetters.iterator().next();
            assertThat(record.topic()).isEqualTo(Topics.POSITIONS_DLT);
            assertThat(record.partition()).isZero();
            assertThat(record.key()).isEqualTo("366000099");
            assertThat(record.value()).isEqualTo("malformed-json");
            assertThat(dlt.poll(Duration.ofMillis(200))).isEmpty();
        } finally {
            container.stop();
        }
    }

    @Test
    void redisFailureRetriesWithoutDltOrOffsetCommitUntilRedisSucceeds() throws Exception {
        var position = point(366000000, 3000);
        long offset = send(position);
        String group = "infrastructure-test-" + UUID.randomUUID();
        var attempted = new AtomicBoolean();
        var available = new AtomicBoolean();
        var store =
                new RedisPositionStore(redis) {
                    @Override
                    public List<Boolean> apply(List<Position> fullBatch) {
                        attempted.set(true);
                        if (!available.get()) {
                            throw new DataAccessResourceFailureException("Redis is unavailable");
                        }
                        return super.apply(fullBatch);
                    }
                };
        var listener =
                new PositionListener(
                        store,
                        new SimpleMeterRegistry(),
                        TestGeofences.empty(),
                        changes -> true,
                        dev.fleet.common.FaultInjector.disabled());
        var container = container(group);
        container.setupMessageListener(
                (BatchAcknowledgingMessageListener<String, String>) listener::onBatch);
        try (var dlt = dltConsumer()) {
            container.start();
            await().atMost(Duration.ofSeconds(20)).until(attempted::get);
            var offsets =
                    admin.listConsumerGroupOffsets(group)
                            .partitionsToOffsetAndMetadata()
                            .get(10, TimeUnit.SECONDS);
            var committed = offsets.get(new TopicPartition(Topics.POSITIONS, 0));
            assertThat(committed == null || committed.offset() <= offset).isTrue();
            assertThat(redis.hasKey(RedisKeys.latest(1, position.assetId()))).isFalse();
            assertThat(dlt.poll(Duration.ofMillis(300))).isEmpty();
            available.set(true);
            awaitCommitted(group, offset + 1);
            assertThat(latest(position)).isEqualTo(position);
            assertThat(dlt.poll(Duration.ofMillis(200))).isEmpty();
        } finally {
            container.stop();
        }
    }

    private org.springframework.kafka.listener.ConcurrentMessageListenerContainer<String, String>
            container(String group) {
        var consumers =
                new DefaultKafkaConsumerFactory<>(
                        Map.of(
                                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers(),
                                ConsumerConfig.GROUP_ID_CONFIG,
                                group,
                                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                                false,
                                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                                "earliest"),
                        new StringDeserializer(),
                        new StringDeserializer());
        var factory =
                new KafkaConfiguration()
                        .kafkaListenerContainerFactory(consumers, kafka, TestGeofences.empty());
        factory.setConcurrency(1);
        return factory.createContainer(Topics.POSITIONS);
    }

    private KafkaConsumer<String, String> dltConsumer() {
        var consumer =
                new KafkaConsumer<>(
                        Map.of(
                                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers(),
                                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                                false,
                                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                                "earliest"),
                        new StringDeserializer(),
                        new StringDeserializer());
        consumer.assign(List.of(new TopicPartition(Topics.POSITIONS_DLT, 0)));
        consumer.seekToBeginning(consumer.assignment());
        return consumer;
    }

    private long send(Position position) throws Exception {
        return kafka.send(
                        new ProducerRecord<>(
                                Topics.POSITIONS,
                                0,
                                Long.toString(position.mmsi()),
                                Json.write(position)))
                .get(10, TimeUnit.SECONDS)
                .getRecordMetadata()
                .offset();
    }

    private void awaitCommitted(String group, long offset) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            var offsets =
                                    admin.listConsumerGroupOffsets(group)
                                            .partitionsToOffsetAndMetadata()
                                            .get(10, TimeUnit.SECONDS);
                            assertThat(offsets.get(new TopicPartition(Topics.POSITIONS, 0)))
                                    .isNotNull();
                            assertThat(
                                            offsets.get(new TopicPartition(Topics.POSITIONS, 0))
                                                    .offset())
                                    .isEqualTo(offset);
                        });
    }

    private Position point(long mmsi, long timestamp) {
        return new Position(
                1,
                mmsi,
                mmsi,
                timestamp,
                40.66,
                -74.05,
                null,
                null,
                null,
                System.currentTimeMillis());
    }

    private Position latest(Position position) {
        String payload =
                (String)
                        redis.opsForHash().get(RedisKeys.latest(1, position.assetId()), "position");
        return Json.read(payload, Position.class);
    }
}
