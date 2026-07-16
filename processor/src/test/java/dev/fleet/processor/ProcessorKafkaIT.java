package dev.fleet.processor;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import dev.fleet.common.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Testcontainers
class ProcessorKafkaIT {
    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.9")).withExposedPorts(6379);

    @Test
    void replaysAfterRedisSucceededButOffsetWasNotCommittedAndSurvivesRebalance() throws Exception {
        try (Admin admin =
                Admin.create(
                        Map.of(
                                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers()))) {
            admin.createTopics(
                            List.of(
                                    new NewTopic(
                                            Topics.POSITIONS,
                                            Topics.POSITION_PARTITIONS,
                                            (short) 1)))
                    .all()
                    .get(20, TimeUnit.SECONDS);
            var redisConnection =
                    new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
            redisConnection.afterPropertiesSet();
            redisConnection.start();
            var redis = new StringRedisTemplate(redisConnection);
            var failureSeen = new AtomicBoolean();
            var processedFullBatches = new CopyOnWriteArrayList<List<Position>>();
            var faultyStore =
                    new RedisPositionStore(redis) {
                        @Override
                        public List<Boolean> apply(List<Position> fullBatch) {
                            var flags = super.apply(fullBatch);
                            processedFullBatches.add(List.copyOf(fullBatch));
                            if (failureSeen.compareAndSet(false, true)) {
                                throw new IllegalStateException(
                                        "Simulated process failure after Redis, before commit");
                            }
                            return flags;
                        }
                    };
            var normalStore = new RedisPositionStore(redis);
            var properties = new HashMap<String, Object>();
            properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
            properties.put(ConsumerConfig.GROUP_ID_CONFIG, "processor-restart-test");
            properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            var factory =
                    new KafkaConfiguration()
                            .kafkaListenerContainerFactory(
                                    new DefaultKafkaConsumerFactory<>(
                                            properties,
                                            new StringDeserializer(),
                                            new StringDeserializer()),
                                    new KafkaTemplate<>(
                                            new DefaultKafkaProducerFactory<>(
                                                    Map.of(
                                                            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                                            KAFKA.getBootstrapServers()),
                                                    new StringSerializer(),
                                                    new StringSerializer())),
                                    TestGeofences.empty());
            factory.setConcurrency(1);
            var first = factory.createContainer(Topics.POSITIONS);
            var second = factory.createContainer(Topics.POSITIONS);
            var firstListener =
                    new PositionListener(
                            faultyStore,
                            new SimpleMeterRegistry(),
                            TestGeofences.empty(),
                            changes -> true,
                            dev.fleet.common.FaultInjector.disabled());
            first.setupMessageListener(
                    (BatchAcknowledgingMessageListener<String, String>) firstListener::onBatch);
            second.setupMessageListener(
                    (BatchAcknowledgingMessageListener<String, String>)
                            new PositionListener(
                                            normalStore,
                                            new SimpleMeterRegistry(),
                                            TestGeofences.empty(),
                                            changes -> true,
                                            dev.fleet.common.FaultInjector.disabled())
                                    ::onBatch);
            // Stop instead of retrying in the first container, leaving this batch uncommitted.
            first.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
            first.start();
            var producerProperties = new Properties();
            producerProperties.put(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
            producerProperties.put(ProducerConfig.ACKS_CONFIG, "all");
            try (var producer =
                    new KafkaProducer<>(
                            producerProperties, new StringSerializer(), new StringSerializer())) {
                var p =
                        new Position(
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
                var metadata =
                        producer.send(
                                        new ProducerRecord<>(
                                                Topics.POSITIONS,
                                                Long.toString(p.mmsi()),
                                                Json.write(p)))
                                .get(20, TimeUnit.SECONDS);
                var partition = new TopicPartition(Topics.POSITIONS, metadata.partition());
                await().atMost(Duration.ofSeconds(30)).until(failureSeen::get);
                await().atMost(Duration.ofSeconds(20)).until(() -> !first.isRunning());
                var offsets =
                        admin.listConsumerGroupOffsets("processor-restart-test")
                                .partitionsToOffsetAndMetadata()
                                .get(10, TimeUnit.SECONDS);
                assertThat(
                                offsets.get(partition) == null
                                        || offsets.get(partition).offset() <= metadata.offset())
                        .isTrue();
                second.start();
                await().atMost(Duration.ofSeconds(30))
                        .untilAsserted(
                                () -> {
                                    var committed =
                                            admin.listConsumerGroupOffsets("processor-restart-test")
                                                    .partitionsToOffsetAndMetadata()
                                                    .get(10, TimeUnit.SECONDS);
                                    assertThat(committed.get(partition)).isNotNull();
                                    assertThat(committed.get(partition).offset())
                                            .isEqualTo(metadata.offset() + 1);
                                });
                assertThat(redis.opsForHash().get(RedisKeys.latest(1, p.assetId()), "position_ts"))
                        .isEqualTo("1000");
                // Rejoin the original consumer to force a group rebalance, then continue
                // processing.
                first.setCommonErrorHandler(
                        new DefaultErrorHandler(
                                new org.springframework.util.backoff.FixedBackOff(
                                        100, Long.MAX_VALUE)));
                first.start();
                await().atMost(Duration.ofSeconds(30))
                        .until(
                                () ->
                                        first.getAssignedPartitions() != null
                                                && second.getAssignedPartitions() != null
                                                && first.getAssignedPartitions().size() == 6
                                                && second.getAssignedPartitions().size() == 6);
                var newer =
                        new Position(
                                1,
                                p.assetId(),
                                p.mmsi(),
                                2000,
                                40.67,
                                -74.04,
                                null,
                                null,
                                null,
                                System.currentTimeMillis());
                var next =
                        producer.send(
                                        new ProducerRecord<>(
                                                Topics.POSITIONS,
                                                Long.toString(p.mmsi()),
                                                Json.write(newer)))
                                .get(20, TimeUnit.SECONDS);
                await().atMost(Duration.ofSeconds(30))
                        .untilAsserted(
                                () -> {
                                    assertThat(
                                                    redis.opsForHash()
                                                            .get(
                                                                    RedisKeys.latest(
                                                                            1, p.assetId()),
                                                                    "position_ts"))
                                            .isEqualTo("2000");
                                    var committed =
                                            admin.listConsumerGroupOffsets("processor-restart-test")
                                                    .partitionsToOffsetAndMetadata()
                                                    .get(10, TimeUnit.SECONDS);
                                    assertThat(committed.get(partition).offset())
                                            .isEqualTo(next.offset() + 1);
                                });
                assertThat(processedFullBatches.getFirst()).containsExactly(p);
            } finally {
                first.stop();
                second.stop();
                redisConnection.destroy();
            }
        }
    }
}
