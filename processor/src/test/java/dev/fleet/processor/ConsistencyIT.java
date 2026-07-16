package dev.fleet.processor;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

import dev.fleet.common.*;
import dev.fleet.common.outbox.OutboxRelay;
import dev.fleet.processor.geofence.*;

import io.micrometer.core.instrument.MeterRegistry;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {ProcessorApplication.class, ConsistencyIT.Faults.class},
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "outbox.initial-delay-ms=3600000",
            "fence-cache.reconcile-interval-ms=3600000"
        })
class ConsistencyIT {
    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0.46"));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.9")).withExposedPorts(6379);

    private static final TopicPartition SOURCE = new TopicPartition(Topics.POSITIONS, 0);
    private static boolean initialized;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        if (!initialized) {
            try (var connection =
                    DriverManager.getConnection(
                            MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
                for (var migration :
                        List.of(
                                "V1__schema.sql",
                                "V2__demo_tenants.sql",
                                "V3__demo_port_fences.sql",
                                "V4__topic_owned_outbox_index.sql",
                                "V5__outbox_trace_context.sql")) {
                    ScriptUtils.executeSqlScript(
                            connection,
                            new FileSystemResource(
                                    "../api/src/main/resources/db/migration/" + migration));
                }
            }
            try (var admin =
                    Admin.create(
                            Map.of(
                                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                    KAFKA.getBootstrapServers()))) {
                admin.createTopics(
                                List.of(
                                        new NewTopic(Topics.POSITIONS, 12, (short) 1),
                                        new NewTopic(Topics.POSITIONS_DLT, 12, (short) 1),
                                        new NewTopic(Topics.FENCE_EVENTS, 12, (short) 1),
                                        new NewTopic(Topics.FENCE_CHANGES, 12, (short) 1)))
                        .all()
                        .get(15, TimeUnit.SECONDS);
            }
            initialized = true;
        }
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    static class Crash extends RuntimeException {}

    static final class TestFaultInjector implements FaultInjector {
        final AtomicReference<Point> armed = new AtomicReference<>();

        @Override
        public void hit(Point point) {
            if (armed.compareAndSet(point, null)) {
                throw new Crash();
            }
        }
    }

    @TestConfiguration
    static class Faults {
        @Bean
        @Primary
        TestFaultInjector testFaultInjector() {
            return new TestFaultInjector();
        }
    }

    @Autowired org.springframework.kafka.config.KafkaListenerEndpointRegistry listeners;
    @Autowired JdbcTemplate sql;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired RedisPositionStore redisStore;
    @Autowired StringRedisTemplate redis;
    @Autowired StateMapper mapper;
    @Autowired FenceMapper fences;
    @Autowired TransitionSink writer;
    @Autowired FenceIndex index;
    @Autowired GeofenceSettings settings;
    @Autowired MeterRegistry metrics;
    @Autowired PlatformTransactionManager manager;
    @Autowired TestFaultInjector faults;
    @Autowired ConsumerFactory<String, String> consumers;

    @Test
    void positionListenerUsesTheConfiguredProcessorGroup() {
        assertThat(listeners.getListenerContainer("position-listener").getGroupId())
                .isEqualTo("fleet-processor");
    }

    @BeforeEach
    void reset() throws Exception {
        faults.armed.set(null);
        sql.update("DELETE FROM outbox");
        sql.update("DELETE FROM geofence_event");
        sql.update("DELETE FROM asset_fence_state");
        sql.update("DELETE FROM asset");
        sql.update(
                "INSERT INTO asset (id, tenant_id, mmsi, name) VALUES (366000000, 1, 366000000,"
                        + " 'Test vessel'), (366000003, 1, 366000003, 'Second vessel')");
        sql.update(
                "UPDATE fence SET deleted = false, version = 1, enter_radius_m = 2000,"
                        + " exit_radius_m = 3000 WHERE id = 1");
        index.put(new Fence(1, 1, "Los Angeles", 33.73, -118.2625, 2000, 3000, 1));
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        try (var admin =
                Admin.create(
                        Map.of(
                                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers()))) {
            long end =
                    admin.listOffsets(Map.of(SOURCE, OffsetSpec.latest()))
                            .partitionResult(SOURCE)
                            .get()
                            .offset();
            admin.deleteRecords(Map.of(SOURCE, RecordsToDelete.beforeOffset(end))).all().get();
        }
    }

    private Position point(long asset, long ts, boolean inside) {
        return new Position(
                1, asset, asset, ts, inside ? 33.73 : 34.0, -118.2625, null, null, null, ts);
    }

    private List<Position> entry() {
        return List.of(
                point(366000000, 1000, true),
                point(366000000, 61_000, true),
                point(366000000, 121_000, true));
    }

    private long publish(List<Position> points) throws Exception {
        long last = -1;
        for (var point : points) {
            last =
                    kafka.send(
                                    new ProducerRecord<>(
                                            Topics.POSITIONS,
                                            0,
                                            Long.toString(point.assetId()),
                                            Json.write(point)))
                            .get(10, TimeUnit.SECONDS)
                            .getRecordMetadata()
                            .offset();
        }
        return last + 1;
    }

    private KafkaConsumer<String, String> source(String group) {
        var consumer = consumer(group);
        consumer.assign(List.of(SOURCE));
        return consumer;
    }

    private KafkaConsumer<String, String> consumer(String group) {
        return new KafkaConsumer<>(
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
    }

    private KafkaConsumer<String, String> downstream() {
        var consumer = consumer("downstream-" + UUID.randomUUID());
        var partitions = new ArrayList<TopicPartition>();
        for (int p = 0; p < 12; p++) {
            partitions.add(new TopicPartition(Topics.FENCE_EVENTS, p));
        }
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position);
        return consumer;
    }

    private List<ConsumerRecord<String, String>> read(
            KafkaConsumer<String, String> consumer, int count) {
        var records = new ArrayList<ConsumerRecord<String, String>>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (records.size() < count && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(200)).forEach(records::add);
        }
        assertThat(records).hasSize(count);
        return records;
    }

    private PositionListener listener(GeofenceEngine engine, TransitionSink sink) {
        return new PositionListener(redisStore, metrics, engine, sink, faults);
    }

    private GeofenceEngine engine() {
        return new GeofenceEngine(index, settings, mapper);
    }

    private void apply(
            PositionListener listener,
            KafkaConsumer<String, String> consumer,
            List<ConsumerRecord<String, String>> records) {
        listener.onBatch(
                records,
                () ->
                        consumer.commitSync(
                                Map.of(
                                        SOURCE,
                                        new OffsetAndMetadata(records.getLast().offset() + 1))));
    }

    private OutboxRelay relay(FaultInjector injector, int batchSize) {
        return relay(injector, Topics.FENCE_EVENTS, batchSize);
    }

    private OutboxRelay relay(FaultInjector injector, String topic, int batchSize) {
        return new OutboxRelay(
                sql, new TransactionTemplate(manager), kafka, injector, topic, batchSize);
    }

    private int count(String table) {
        return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertOneEvent(KafkaConsumer<String, String> downstream, int deliveries) {
        var records = read(downstream, deliveries);
        assertThat(records)
                .allSatisfy(
                        record -> {
                            assertThat(record.key()).isEqualTo("366000000");
                            assertThat(record.value())
                                    .contains("\"event_id\"", "\"fence_version\"");
                            var event = Json.read(record.value(), GeofenceEvent.class);
                            assertThat(event.tenantId()).isEqualTo(1);
                            assertThat(event.fenceId()).isEqualTo(1);
                            assertThat(event.fenceVersion()).isEqualTo(1);
                        });
        var ids =
                records.stream()
                        .map(record -> Json.read(record.value(), GeofenceEvent.class).eventId())
                        .collect(java.util.stream.Collectors.toSet());
        assertThat(ids).hasSize(1);
        assertThat(ids)
                .containsExactly(
                        sql.queryForObject("SELECT event_id FROM geofence_event", String.class));
        assertThat(count("geofence_event")).isEqualTo(1);
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL",
                                Integer.class))
                .isZero();
    }

    @ParameterizedTest
    @EnumSource(FaultInjector.Point.class)
    void crashAtEachBoundaryThenRestartProducesOneDatabaseAndOneDeduplicatedEvent(
            FaultInjector.Point point) throws Exception {
        String group = "crash-" + UUID.randomUUID();
        long end = publish(entry());
        try (var downstream = downstream()) {
            if (point != FaultInjector.Point.AFTER_PUBLISH_BEFORE_MARK) {
                faults.armed.set(point);
                try (var first = source(group)) {
                    var batch = read(first, 3);
                    assertThatThrownBy(() -> apply(listener(engine(), writer), first, batch))
                            .isInstanceOf(Crash.class);
                    assertThat(first.committed(Set.of(SOURCE)).get(SOURCE)).isNull();
                }
                assertThat(count("geofence_event"))
                        .isEqualTo(point == FaultInjector.Point.BEFORE_TX ? 0 : 1);
                try (var restarted = source(group)) {
                    apply(listener(engine(), writer), restarted, read(restarted, 3));
                    assertThat(restarted.committed(Set.of(SOURCE)).get(SOURCE).offset())
                            .isEqualTo(end);
                }
            } else {
                try (var first = source(group)) {
                    apply(listener(engine(), writer), first, read(first, 3));
                }
                faults.armed.set(point);
                assertThatThrownBy(() -> relay(faults, 100).poll()).isInstanceOf(Crash.class);
                assertThat(
                                sql.queryForObject(
                                        "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL",
                                        Integer.class))
                        .isEqualTo(1);
            }
            relay(FaultInjector.disabled(), 100).poll();
            assertOneEvent(
                    downstream, point == FaultInjector.Point.AFTER_PUBLISH_BEFORE_MARK ? 2 : 1);
            assertThat(sql.queryForObject("SELECT inside FROM asset_fence_state", Boolean.class))
                    .isTrue();
        }
    }

    @Test
    void restartInsideDoesNotProduceAnotherEnterAtALaterPositionTimestamp() throws Exception {
        String group = "inside-" + UUID.randomUUID();
        publish(entry());
        try (var first = source(group)) {
            apply(listener(engine(), writer), first, read(first, 3));
        }
        publish(
                List.of(
                        point(366000000, 181_000, true),
                        point(366000000, 241_000, true),
                        point(366000000, 301_000, true)));
        try (var restarted = source(group)) {
            apply(listener(engine(), writer), restarted, read(restarted, 3));
        }
        assertThat(count("geofence_event")).isEqualTo(1);
        assertThat(
                        sql.queryForObject(
                                "SELECT last_transition_position_ts FROM asset_fence_state",
                                Long.class))
                .isEqualTo(121_000);
    }

    @Test
    void databaseFailureMidBatchRollsBackAndSameEngineRetryRebuildsFromMysql() throws Exception {
        String group = "rollback-" + UUID.randomUUID();
        var points = new ArrayList<>(entry());
        points.addAll(
                List.of(
                        point(366000000, 181_000, false),
                        point(366000000, 241_000, false),
                        point(366000000, 301_000, false)));
        publish(points);
        var delegate = mock(StateMapper.class, delegatesTo(mapper));
        var calls = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (calls.incrementAndGet() == 2) {
                                sql.execute(
                                        "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test MySQL"
                                                + " failure mid batch'");
                            }
                            return mapper.insertEvent(invocation.getArgument(0));
                        })
                .when(delegate)
                .insertEvent(any());
        var failingWriter =
                new TransactionalTransitionWriter(
                        delegate, new TransactionTemplate(manager), metrics);
        var listener = listener(engine(), failingWriter);
        double before =
                metrics.get("geofence.transitions")
                        .tags("mode", "hysteresis", "type", "ENTER")
                        .counter()
                        .count();
        try (var consumer = source(group);
                var downstream = downstream()) {
            var batch = read(consumer, 6);
            assertThatThrownBy(() -> apply(listener, consumer, batch))
                    .isInstanceOf(DataAccessException.class);
            assertThat(count("asset_fence_state")).isZero();
            assertThat(count("geofence_event")).isZero();
            assertThat(count("outbox")).isZero();
            assertThat(
                            metrics.get("geofence.transitions")
                                    .tags("mode", "hysteresis", "type", "ENTER")
                                    .counter()
                                    .count())
                    .isEqualTo(before);
            assertThat(consumer.committed(Set.of(SOURCE)).get(SOURCE)).isNull();
            apply(listener, consumer, batch);
            assertThat(count("geofence_event")).isEqualTo(2);
            assertThat(
                            metrics.get("geofence.transitions")
                                    .tags("mode", "hysteresis", "type", "ENTER")
                                    .counter()
                                    .count())
                    .isEqualTo(before + 1);
            assertThat(sql.queryForObject("SELECT inside FROM asset_fence_state", Boolean.class))
                    .isFalse();
            assertThat(
                            sql.queryForObject(
                                    "SELECT last_transition_position_ts FROM asset_fence_state",
                                    Long.class))
                    .isEqualTo(301_000);
            relay(FaultInjector.disabled(), 100).poll();
            assertThat(
                            read(downstream, 2).stream()
                                    .map(
                                            record ->
                                                    Json.read(record.value(), GeofenceEvent.class)
                                                            .type()))
                    .containsExactlyInAnyOrder(GeofenceEvent.Type.ENTER, GeofenceEvent.Type.EXIT);
        }
    }

    @Test
    void concurrentRelaysSkipRowsLockedByAnotherInstance() throws Exception {
        writer.persist(
                List.of(
                        new Transition(
                                1,
                                366000000,
                                1,
                                1,
                                1000,
                                Transition.Type.ENTER,
                                GeofenceSettings.Mode.NAIVE),
                        new Transition(
                                1,
                                366000003,
                                1,
                                1,
                                1000,
                                Transition.Type.ENTER,
                                GeofenceSettings.Mode.NAIVE)));
        var published = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        FaultInjector holding =
                point -> {
                    published.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test relay timeout");
                        }
                    } catch (InterruptedException error) {
                        throw new IllegalStateException(error);
                    }
                };
        try (var downstream = downstream()) {
            var first = CompletableFuture.runAsync(() -> relay(holding, 1).poll());
            try {
                assertThat(published.await(10, TimeUnit.SECONDS)).isTrue();
                relay(FaultInjector.disabled(), 1).poll();
                assertThat(
                                sql.queryForObject(
                                        "SELECT COUNT(*) FROM outbox WHERE published_at IS NOT"
                                                + " NULL",
                                        Integer.class))
                        .isEqualTo(1);
            } finally {
                release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
            assertThat(
                            read(downstream, 2).stream()
                                    .map(
                                            record ->
                                                    Json.read(record.value(), GeofenceEvent.class)
                                                            .eventId())
                                    .distinct())
                    .hasSize(2);
            assertThat(
                            sql.queryForObject(
                                    "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL",
                                    Integer.class))
                    .isZero();
        }
    }

    @Test
    void fenceChangeBroadcastReachesBothInstancesAndDeletionDoesNotEmitExit() throws Exception {
        var firstIndex = new FenceIndex();
        var secondIndex = new FenceIndex();
        fences.loadActive().forEach(firstIndex::put);
        fences.loadActive().forEach(secondIndex::put);
        var firstCache = new FenceCache(fences, firstIndex, metrics);
        var secondCache = new FenceCache(fences, secondIndex, metrics);
        var factory = new KafkaConfiguration().fenceChangeFactory(consumers);
        var first = factory.createContainer(Topics.FENCE_CHANGES);
        var second = factory.createContainer(Topics.FENCE_CHANGES);
        first.getContainerProperties().setGroupId("fence-instance-a-" + UUID.randomUUID());
        second.getContainerProperties().setGroupId("fence-instance-b-" + UUID.randomUUID());
        first.setupMessageListener(
                (MessageListener<String, String>)
                        record -> firstCache.changed(Json.read(record.value(), FenceChange.class)));
        second.setupMessageListener(
                (MessageListener<String, String>)
                        record ->
                                secondCache.changed(Json.read(record.value(), FenceChange.class)));
        first.start();
        second.start();
        try {
            await().atMost(Duration.ofSeconds(20))
                    .until(
                            () ->
                                    first.getAssignedPartitions() != null
                                            && first.getAssignedPartitions().size() == 12
                                            && second.getAssignedPartitions() != null
                                            && second.getAssignedPartitions().size() == 12);
            sql.update("UPDATE fence SET version = 2, enter_radius_m = 2500 WHERE id = 1");
            sql.update(
                    "INSERT INTO outbox (tenant_id, topic, msg_key, payload) VALUES (1,"
                            + " 'fence-changes', '1', ?)",
                    Json.write(new FenceChange(1, 1, 2, false)));
            relay(FaultInjector.disabled(), Topics.FENCE_CHANGES, 100).poll();
            await().atMost(Duration.ofSeconds(10))
                    .until(
                            () ->
                                    firstIndex.get(1).version() == 2
                                            && secondIndex.get(1).version() == 2);
            // Establish INSIDE before deleting the fence, then process an outside report.
            writer.persist(
                    List.of(
                            new Transition(
                                    1,
                                    366000000,
                                    1,
                                    2,
                                    1000,
                                    Transition.Type.ENTER,
                                    GeofenceSettings.Mode.NAIVE)));
            var engine = new GeofenceEngine(firstIndex, settings, mapper);
            assertThat(
                            engine.processBatch(
                                    List.of(
                                            new PartitionPosition(
                                                    SOURCE, point(366000000, 2000, true)))))
                    .isEmpty();
            sql.update("UPDATE fence SET version = 3, deleted = true WHERE id = 1");
            sql.update(
                    "INSERT INTO outbox (tenant_id, topic, msg_key, payload) VALUES (1,"
                            + " 'fence-changes', '1', ?)",
                    Json.write(new FenceChange(1, 1, 3, true)));
            relay(FaultInjector.disabled(), Topics.FENCE_CHANGES, 100).poll();
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> firstIndex.get(1) == null && secondIndex.get(1) == null);
            assertThat(
                            engine.processBatch(
                                    List.of(
                                            new PartitionPosition(
                                                    SOURCE, point(366000000, 3000, false)))))
                    .isEmpty();
            assertThat(count("geofence_event")).isEqualTo(1);
        } finally {
            first.stop();
            second.stop();
        }
    }


    @Test
    void conditionalStateWriteRejectsOlderAndRepeatedTransitions() {
        var enter =
                new Transition(
                        1,
                        366000000,
                        1,
                        1,
                        2000,
                        Transition.Type.ENTER,
                        GeofenceSettings.Mode.NAIVE);
        writer.persist(List.of(enter));
        writer.persist(
                List.of(
                        enter,
                        new Transition(
                                1,
                                366000000,
                                1,
                                1,
                                1000,
                                Transition.Type.EXIT,
                                GeofenceSettings.Mode.NAIVE)));
        assertThat(count("geofence_event")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT inside FROM asset_fence_state", Boolean.class))
                .isTrue();
        assertThat(
                        sql.queryForObject(
                                "SELECT last_transition_position_ts FROM asset_fence_state",
                                Long.class))
                .isEqualTo(2000);
    }

    @Test
    void relaysPublishOnlyTheirOwnedTopic() {
        sql.update(
                "INSERT INTO outbox (tenant_id, topic, msg_key, payload) VALUES (1, 'fence-events',"
                        + " '366000000', '{}'), (1, 'fence-changes', '1', '{}')");
        relay(FaultInjector.disabled(), Topics.FENCE_CHANGES, 100).poll();
        assertThat(
                        sql.queryForObject(
                                "SELECT published_at IS NULL FROM outbox WHERE topic ="
                                        + " 'fence-events'",
                                Boolean.class))
                .isTrue();
        assertThat(
                        sql.queryForObject(
                                "SELECT published_at IS NOT NULL FROM outbox WHERE topic ="
                                        + " 'fence-changes'",
                                Boolean.class))
                .isTrue();
        relay(FaultInjector.disabled(), 100).poll();
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL",
                                Integer.class))
                .isZero();
    }

    @Test
    void relayEnqueuesWholeBatchBeforeWaitingAndMarksWithOneUpdate() {
        sql.update(
                "INSERT INTO outbox (tenant_id, topic, msg_key, payload) VALUES (1, 'fence-events',"
                        + " '366000000', '{}'), (1, 'fence-events', '366000003', '{}')");
        var producer = mock(KafkaTemplate.class);
        var first =
                new CompletableFuture<
                        org.springframework.kafka.support.SendResult<String, String>>();
        var calls = new AtomicInteger();
        when(producer.send(any(ProducerRecord.class)))
                .thenAnswer(
                        invocation -> {
                            if (calls.incrementAndGet() == 1) {
                                return first;
                            }
                            first.complete(null);
                            return CompletableFuture.completedFuture(null);
                        });
        var monitored = spy(sql);
        new OutboxRelay(
                        monitored,
                        new TransactionTemplate(manager),
                        producer,
                        FaultInjector.disabled(),
                        Topics.FENCE_EVENTS,
                        100)
                .poll();
        assertThat(calls.get()).isEqualTo(2);
        verify(monitored, times(1)).update(contains("WHERE id IN"), any(Object[].class));
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM outbox WHERE published_at IS NOT NULL",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void failedBatchAckLeavesAllRowsPendingEvenIfAnEarlierSendSucceeded() {
        sql.update(
                "INSERT INTO outbox (tenant_id, topic, msg_key, payload) VALUES (1, 'fence-events',"
                        + " '366000000', '{}'), (1, 'fence-events', '366000003', '{}')");
        var producer = mock(KafkaTemplate.class);
        when(producer.send(any(ProducerRecord.class)))
                .thenReturn(
                        CompletableFuture.completedFuture(null),
                        CompletableFuture.failedFuture(new IllegalStateException("Kafka failure")));
        var relay =
                new OutboxRelay(
                        sql,
                        new TransactionTemplate(manager),
                        producer,
                        FaultInjector.disabled(),
                        Topics.FENCE_EVENTS,
                        100);
        assertThatThrownBy(relay::poll).isInstanceOf(IllegalStateException.class);
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL",
                                Integer.class))
                .isEqualTo(2);
        relay(FaultInjector.disabled(), 100).poll();
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM outbox WHERE published_at IS NULL",
                                Integer.class))
                .isZero();
    }
}
