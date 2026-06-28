package dev.fleet.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.fleet.api.db.AssetMapper;
import dev.fleet.api.db.TenantMapper;
import dev.fleet.common.*;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "outbox.initial-delay-ms=3600000",
            "internal.grpc-port=0",
            "OPENAI_API_KEY=",
            "ANTHROPIC_API_KEY="
        })
class IngestIT {
    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0.46"))
                    .withCommand("--log-bin-trust-function-creators=1");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.9")).withExposedPorts(6379);

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate sql;
    @Autowired StringRedisTemplate redis;
    @Autowired RateLimiter limiter;
    @Autowired dev.fleet.api.copilot.CopilotStore copilotStore;
    @Autowired TenantConfigCache tenantConfigs;
    @Autowired AssetRegistry assets;
    @Autowired dev.fleet.api.internal.AiReadDatabase aiRead;
    @MockitoSpyBean TenantMapper tenantMapper;
    @MockitoSpyBean AssetMapper assetMapper;

    @BeforeEach
    void reset() {
        ((Map<?, ?>) ReflectionTestUtils.getField(tenantConfigs, "cache")).clear();
        ((Map<?, ?>) ReflectionTestUtils.getField(assets, "confirmedOwners")).clear();
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        sql.update("DELETE FROM geofence_event");
        sql.update("DELETE FROM asset_fence_state");
        sql.update("DELETE FROM outbox");
        sql.update("DELETE FROM asset");
        sql.update("UPDATE tenant SET ingest_rate_per_sec = 1000");
    }

    private IngestPoint point(long mmsi) {
        return new IngestPoint(
                mmsi, 1000, 40.66, -74.05, 8.0, 90.0, 90.0, System.currentTimeMillis());
    }

    @Test
    void apiNoLongerExposesCopilotChat() {
        var response =
                http.postForEntity(
                        "/copilot/chat",
                        Map.of("tenantId", 1, "userId", "demo", "message", "List ports"),
                        String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }


    @Test
    void resumePagesAreTenantScopedAssetFilteredAndOrderedByCreatedAtThenRowId() throws Exception {
        sql.update(
                "INSERT INTO asset (id, tenant_id, mmsi, name) VALUES "
                        + "(366000000, 1, 366000000, 'one'), (366000003, 1, 366000003, 'three'), "
                        + "(366000001, 2, 366000001, 'two')");
        for (String id : List.of("a", "b", "c", "d")) {
            boolean foreign = id.equals("d");
            sql.update(
                    "INSERT INTO geofence_event (event_id, tenant_id, asset_id, fence_id, type,"
                        + " position_ts, fence_version, created_at) VALUES (?, ?, ?, ?, 'ENTER', ?,"
                        + " 1, ?)",
                    id.repeat(64),
                    foreign ? 2 : 1,
                    foreign ? 366000001L : id.equals("b") ? 366000003L : 366000000L,
                    foreign ? 2 : 1,
                    id.equals("c") ? 2000 : 1000,
                    id.equals("c") ? "2026-10-08 12:00:02.123456" : "2026-10-08 12:00:01.123456");
        }
        String name = io.grpc.inprocess.InProcessServerBuilder.generateName();
        var server =
                io.grpc.inprocess.InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(
                                new dev.fleet.api.internal.FleetDataService(
                                        new dev.fleet.api.internal.FleetQueries(
                                                copilotStore, redis, 50000),
                                        tenantConfigs, aiRead))
                        .build()
                        .start();
        var channel =
                io.grpc.inprocess.InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            var stub =
                    dev.fleet.common.rpc.FleetDataGrpc.newBlockingStub(channel)
                            .withDeadlineAfter(2, TimeUnit.SECONDS);
            var request =
                    dev.fleet.common.rpc.EventsSinceRequest.newBuilder()
                            .setTenantId(1)
                            .setAll(true)
                            .setAfterEventId("a".repeat(64))
                            .setLimit(1)
                            .setLookBackMicros(0);
            var first = stub.listEventsSince(request.build());
            assertThat(first.getEventsList())
                    .extracting(dev.fleet.common.rpc.GeofenceEvent::getEventId)
                    .containsExactly("a".repeat(64));
            var second = stub.listEventsSince(request.setPageAfter(first.getNext()).build());
            assertThat(second.getEventsList())
                    .extracting(dev.fleet.common.rpc.GeofenceEvent::getEventId)
                    .containsExactly("b".repeat(64));
            var third = stub.listEventsSince(request.setPageAfter(second.getNext()).build());
            assertThat(third.getEventsList())
                    .extracting(dev.fleet.common.rpc.GeofenceEvent::getEventId)
                    .containsExactly("c".repeat(64));
            assertThat(
                            stub.listEventsSince(request.setPageAfter(third.getNext()).build())
                                    .getEventsCount())
                    .isZero();
            var filtered =
                    stub.listEventsSince(
                            request.clearPageAfter()
                                    .setAfterEventId("a".repeat(64))
                                    .setAll(false)
                                    .addAssetIds(366000000L)
                                    .setLimit(500)
                                    .build());
            assertThat(filtered.getEventsList())
                    .extracting(dev.fleet.common.rpc.GeofenceEvent::getEventId)
                    .containsExactly("a".repeat(64), "c".repeat(64));
            assertThatThrownBy(() -> stub.listEventsSince(request.setTenantId(2).build()))
                    .hasMessageContaining("INVALID_ARGUMENT");
            assertThatThrownBy(
                            () ->
                                    stub.listEventsSince(
                                            request.clearAssetIds().setAll(true).build()))
                    .hasMessageContaining("INVALID_ARGUMENT");
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    void smallerIdTransactionCommittingAfterLargerIdIsVisibleWithinOverlap() throws Exception {
        sql.update(
                "INSERT INTO asset (id, tenant_id, mmsi, name) VALUES "
                        + "(366000000, 1, 366000000, 'one'), (366000003, 1, 366000003, 'three')");
        var queries = new dev.fleet.api.internal.FleetQueries(copilotStore, redis, 50000);
        String insert =
                "INSERT INTO geofence_event (event_id, tenant_id, asset_id, fence_id, type,"
                    + " position_ts, fence_version, created_at) VALUES (?, 1, ?, 1, 'ENTER', ?, 1,"
                    + " ?)";
        try (var first = sql.getDataSource().getConnection()) {
            first.setAutoCommit(false);
            try (var statement = first.prepareStatement(insert)) {
                statement.setString(1, "a".repeat(64));
                statement.setLong(2, 366000003L);
                statement.setLong(3, 1000);
                statement.setString(4, "2026-10-08 12:00:00.123456");
                statement.executeUpdate();
            }
            // Different connection commits B while A's lower ID remains invisible.
            sql.update(insert, "b".repeat(64), 366000000L, 3000, "2026-10-08 12:00:02.123456");
            var b = copilotStore.eventById(1, "b".repeat(64));
            var request =
                    dev.fleet.common.rpc.EventsSinceRequest.newBuilder()
                            .setTenantId(1)
                            .setAll(true)
                            .setAfter(
                                    dev.fleet.common.rpc.EventCursor.newBuilder()
                                            .setCreatedAtMicros(b.createdAtMicros())
                                            .setId(b.id()))
                            .setLimit(50)
                            .build();
            assertThat(queries.eventsSince(request).getEventsList())
                    .extracting(dev.fleet.common.rpc.GeofenceEvent::getEventId)
                    .containsExactly("b".repeat(64));
            first.commit();
            var a = copilotStore.eventById(1, "a".repeat(64));
            assertThat(a.id()).isLessThan(b.id());
            assertThat(a.createdAtMicros()).isLessThan(b.createdAtMicros());
            assertThat(queries.eventsSince(request).getEventsList())
                    .extracting(dev.fleet.common.rpc.GeofenceEvent::getEventId)
                    .containsExactly("a".repeat(64), "b".repeat(64));
            // Zero overlap demonstrates the original visibility gap.
            assertThat(
                            queries.eventsSince(request.toBuilder().setLookBackMicros(0).build())
                                    .getEventsList())
                    .extracting(dev.fleet.common.rpc.GeofenceEvent::getEventId)
                    .containsExactly("b".repeat(64));
        }
    }

    @Test
    void copilotSummariesCountBeyondDetailCapAndIsolateTenants() throws Exception {
        sql.update(
                "INSERT INTO asset (id, tenant_id, mmsi, name) VALUES (366000000, 1, 366000000,"
                        + " 'Atlas'), (366000100, 2, 366000100, 'Atlas')");
        for (int i = 0; i < 60; i++) {
            sql.update(
                    "INSERT INTO"
                        + " geofence_event(event_id,tenant_id,asset_id,fence_id,type,position_ts,fence_version)"
                        + " VALUES (?,1,366000000,1,'ENTER',?,1)",
                    String.format("%064x", i + 1),
                    1000L + i);
        }
        sql.update(
                "INSERT INTO"
                    + " asset_fence_state(asset_id,fence_id,tenant_id,inside,last_transition_position_ts,fence_version)"
                    + " VALUES (366000000,1,1,true,1000,1), (366000100,2,2,true,1000,1)");
        var queries = new dev.fleet.api.internal.FleetQueries(copilotStore, redis, 50000);
        String name = io.grpc.inprocess.InProcessServerBuilder.generateName();
        var server =
                io.grpc.inprocess.InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(
                                new dev.fleet.api.internal.FleetDataService(queries, tenantConfigs, aiRead))
                        .build()
                        .start();
        var channel =
                io.grpc.inprocess.InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            var stub = dev.fleet.common.rpc.FleetDataGrpc.newBlockingStub(channel);
            var request =
                    dev.fleet.common.rpc.EventsRequest.newBuilder()
                            .setTenantId(1)
                            .setFromTs(0)
                            .setToTs(2000)
                            .setEventType("ENTER")
                            .build();
            var generic =
                    dev.fleet.common.rpc.EventQueryRequest.newBuilder()
                            .setTenantId(1)
                            .setMetric("event_count")
                            .setEventType("ENTER")
                            .setFromTs(0)
                            .setToTs(2000)
                            .setGroupBy("port")
                            .addPorts(1)
                            .build();
            var counted = stub.queryEvents(generic);
            assertThat(counted.getTotal()).isEqualTo(60);
            assertThat(counted.getRows(0).getValue()).isEqualTo(60);
            assertThat(counted.getReceipt().getPorts(0).getName()).isEqualTo("Los Angeles");
            assertThat(counted.getReceipt().getArgumentsMap())
                    .containsEntry("fromTs", "0")
                    .containsEntry("ports", "[1]")
                    .containsEntry("groupBy", "port");
            assertThat(
                            stub.queryEvents(
                                            generic.toBuilder()
                                                    .setMetric("distinct_vessels")
                                                    .build())
                                    .getTotal())
                    .isEqualTo(1);
            for (String group : List.of("vessel", "day", "type", "none")) {
                assertThat(
                                stub.queryEvents(generic.toBuilder().setGroupBy(group).build())
                                        .getRows(0)
                                        .getValue())
                        .isEqualTo(60);
            }
            assertThat(
                            stub.queryEvents(generic.toBuilder().setGroupBy("day").build())
                                    .getRows(0)
                                    .getDayUtc())
                    .isEqualTo("1970-01-01");
            assertThatThrownBy(
                            () ->
                                    stub.queryEvents(
                                            generic.toBuilder().clearPorts().addPorts(2).build()))
                    .hasMessageContaining("INVALID_ARGUMENT");
            assertThatThrownBy(
                            () ->
                                    stub.queryEvents(
                                            generic.toBuilder().addVessels(366000100).build()))
                    .hasMessageContaining("INVALID_ARGUMENT");
            assertThatThrownBy(
                            () ->
                                    stub.queryEvents(
                                            generic.toBuilder().setGroupBy("tenant").build()))
                    .hasMessageContaining("INVALID_ARGUMENT");
            assertThatThrownBy(() -> stub.queryEvents(generic.toBuilder().setToTs(-1).build()))
                    .hasMessageContaining("INVALID_ARGUMENT");
            var summary = stub.aggregateEvents(request);
            assertThat(summary.getTotalEvents()).isEqualTo(60);
            assertThat(summary.getDistinctAssets()).isEqualTo(1);
            assertThat(summary.getPorts(0).getEvents()).isEqualTo(60);
            assertThat(stub.listEvents(request).getEventsCount()).isEqualTo(50);
            assertThat(
                            stub.aggregateEvents(request.toBuilder().setEventType("EXIT").build())
                                    .getTotalEvents())
                    .isZero();
            assertThat(
                            stub.aggregateEvents(request.toBuilder().setTenantId(2).build())
                                    .getTotalEvents())
                    .isZero();
            assertThat(
                            stub.findAssets(
                                            dev.fleet.common.rpc.AssetSearchRequest.newBuilder()
                                                    .setTenantId(1)
                                                    .setName("Atlas")
                                                    .build())
                                    .getAssetsList())
                    .extracting(dev.fleet.common.rpc.NamedAsset::getAssetId)
                    .containsExactly(366000000L);
            var state =
                    stub.getFleetState(
                            dev.fleet.common.rpc.FencesRequest.newBuilder().setTenantId(1).build());
            assertThat(state.getInsideAssets()).isEqualTo(1);
            assertThat(state.getAssets(0).getAssetId()).isEqualTo(366000000L);
            for (int i = 1; i <= 60; i++) {
                long asset = 367000000L + i;
                sql.update(
                        "INSERT INTO asset(id,tenant_id,mmsi,name) VALUES (?,1,?,'bulk')",
                        asset,
                        asset);
                sql.update(
                        "INSERT INTO"
                            + " asset_fence_state(asset_id,fence_id,tenant_id,inside,last_transition_position_ts,fence_version)"
                            + " VALUES (?,1,1,true,1000,1)",
                        asset);
            }
            var bounded =
                    stub.getFleetState(
                            dev.fleet.common.rpc.FencesRequest.newBuilder().setTenantId(1).build());
            assertThat(bounded.getInsideAssets()).isEqualTo(61);
            assertThat(bounded.getAssetsCount()).isEqualTo(50);
            assertThat(bounded.getTruncated()).isTrue();
            assertThatThrownBy(
                            () -> stub.aggregateEvents(request.toBuilder().setFenceId(2).build()))
                    .hasMessageContaining("INVALID_ARGUMENT");
            assertThatThrownBy(
                            () ->
                                    stub.aggregateEvents(
                                            request.toBuilder().setAssetId(366000100).build()))
                    .hasMessageContaining("INVALID_ARGUMENT");
            assertThatThrownBy(
                            () ->
                                    stub.aggregateEvents(
                                            request.toBuilder().setEventType("BOGUS").build()))
                    .hasMessageContaining("INVALID_ARGUMENT");
            assertThatThrownBy(() -> stub.aggregateEvents(request.toBuilder().clearToTs().build()))
                    .hasMessageContaining("INVALID_ARGUMENT");
            assertThatThrownBy(
                            () ->
                                    stub.findAssets(
                                            dev.fleet.common.rpc.AssetSearchRequest.newBuilder()
                                                    .setTenantId(1)
                                                    .setName(" ")
                                                    .build()))
                    .hasMessageContaining("INVALID_ARGUMENT");
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    void copilotReadsAreReadOnlyPresentedAndRefusedWhenTheReplicaLags() throws Exception {
        sql.update(
                "INSERT INTO asset (id, tenant_id, mmsi, name) VALUES (366000000, 1, 366000000,"
                        + " 'one')");
        assertThatThrownBy(
                        () -> aiRead.sql().update("DELETE FROM asset WHERE id = 366000000"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        String name = io.grpc.inprocess.InProcessServerBuilder.generateName();
        var service =
                new dev.fleet.api.internal.FleetDataService(
                        new dev.fleet.api.internal.FleetQueries(copilotStore, redis, 50000),
                        tenantConfigs, aiRead);
        var server =
                io.grpc.inprocess.InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(service)
                        .build()
                        .start();
        var channel =
                io.grpc.inprocess.InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            var stub =
                    dev.fleet.common.rpc.FleetDataGrpc.newBlockingStub(channel)
                            .withDeadlineAfter(2, TimeUnit.SECONDS);
            sql.update(
                    "UPDATE replication_heartbeat SET beat_at = CURRENT_TIMESTAMP(6) WHERE id = 1");
            var request =
                    dev.fleet.common.rpc.AssetRequest.newBuilder()
                            .setTenantId(1)
                            .setAssetId(366000000L)
                            .build();
            var presentation = stub.getAssetStatus(request).getPresentation();
            assertThat(presentation.getConditions())
                    .contains("one (MMSI 366000000)")
                    .contains("Latest reported position and confirmed port states");
            assertThat(presentation.getResultJson()).contains("\"assetId\":366000000");
            assertThat(presentation.getTablesList()).isNotEmpty();
            assertThat(java.time.Instant.parse(presentation.getDataAsOfUtc()))
                    .isAfter(java.time.Instant.now().minusSeconds(60));
            sql.update(
                    "UPDATE replication_heartbeat SET beat_at = CURRENT_TIMESTAMP(6) - INTERVAL 61"
                            + " SECOND WHERE id = 1");
            assertThatThrownBy(() -> stub.getAssetStatus(request))
                    .hasMessageContaining("UNAVAILABLE")
                    .hasMessageContaining(dev.fleet.api.internal.FleetDataService.REPLICA_DELAYED);
        } finally {
            sql.update(
                    "UPDATE replication_heartbeat SET beat_at = CURRENT_TIMESTAMP(6) WHERE id = 1");
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
            server.shutdownNow();
        }
    }

    @Test
    void grpcReadsEnforceTenantOwnership() throws Exception {
        sql.update(
                "INSERT INTO asset (id, tenant_id, mmsi, name) VALUES (366000000, 1, 366000000,"
                        + " 'one'), (366000001, 2, 366000001, 'two')");
        String name = io.grpc.inprocess.InProcessServerBuilder.generateName();
        var service =
                new dev.fleet.api.internal.FleetDataService(
                        new dev.fleet.api.internal.FleetQueries(copilotStore, redis, 50000),
                        tenantConfigs, aiRead);
        var server =
                io.grpc.inprocess.InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(service)
                        .build()
                        .start();
        var channel =
                io.grpc.inprocess.InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            var stub =
                    dev.fleet.common.rpc.FleetDataGrpc.newBlockingStub(channel)
                            .withDeadlineAfter(2, TimeUnit.SECONDS);
            assertThat(
                            stub.getAssetStatus(
                                            dev.fleet.common.rpc.AssetRequest.newBuilder()
                                                    .setTenantId(1)
                                                    .setAssetId(366000000L)
                                                    .build())
                                    .getAssetId())
                    .isEqualTo(366000000L);
            assertThatThrownBy(
                            () ->
                                    stub.getAssetStatus(
                                            dev.fleet.common.rpc.AssetRequest.newBuilder()
                                                    .setTenantId(2)
                                                    .setAssetId(366000000L)
                                                    .build()))
                    .isInstanceOf(io.grpc.StatusRuntimeException.class)
                    .hasMessageContaining("INVALID_ARGUMENT");
            assertThatThrownBy(
                            () ->
                                    stub.listEvents(
                                            dev.fleet.common.rpc.EventsRequest.newBuilder()
                                                    .setTenantId(2)
                                                    .setAssetId(366000000L)
                                                    .build()))
                    .hasMessageContaining("INVALID_ARGUMENT");
            assertThatThrownBy(
                            () ->
                                    stub.findNearby(
                                            dev.fleet.common.rpc.NearbyRequest.newBuilder()
                                                    .setTenantId(2)
                                                    .setFenceId(1)
                                                    .setRadiusM(1000)
                                                    .build()))
                    .hasMessageContaining("INVALID_ARGUMENT");
            assertThat(
                            stub.listFences(
                                            dev.fleet.common.rpc.FencesRequest.newBuilder()
                                                    .setTenantId(1)
                                                    .build())
                                    .getFencesList())
                    .extracting(dev.fleet.common.rpc.Fence::getName)
                    .contains("Los Angeles")
                    .doesNotContain("Long Beach");
            assertThat(
                            stub.getTenantPolicy(
                                            dev.fleet.common.rpc.TenantRequest.newBuilder()
                                                    .setTenantId(1)
                                                    .build())
                                    .getLlmRatePerMin())
                    .isPositive();
        } finally {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void typedGrpcResponsesPreserveAbsenceZeroValuesFiltersAndListLimits() throws Exception {
        sql.update(
                "INSERT INTO asset (id, tenant_id, mmsi, name) VALUES (366000000, 1, 366000000,"
                        + " 'one')");
        String name = io.grpc.inprocess.InProcessServerBuilder.generateName();
        var service =
                new dev.fleet.api.internal.FleetDataService(
                        new dev.fleet.api.internal.FleetQueries(copilotStore, redis, 50000),
                        tenantConfigs, aiRead);
        var server =
                io.grpc.inprocess.InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(service)
                        .build()
                        .start();
        var channel =
                io.grpc.inprocess.InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            var stub =
                    dev.fleet.common.rpc.FleetDataGrpc.newBlockingStub(channel)
                            .withDeadlineAfter(2, TimeUnit.SECONDS);
            var request =
                    dev.fleet.common.rpc.AssetRequest.newBuilder()
                            .setTenantId(1)
                            .setAssetId(366000000)
                            .setStateLimit(100)
                            .build();
            assertThat(stub.getAssetStatus(request).hasPosition()).isFalse();
            redis.opsForHash()
                    .put(
                            RedisKeys.latest(1, 366000000),
                            "position",
                            Json.write(
                                    new Position(
                                            1, 366000000, 366000000, 1000, 33.73, -118.2625, 0.0,
                                            null, 90.0, 2000)));
            redis.opsForGeo()
                    .add(
                            RedisKeys.geo(1),
                            new org.springframework.data.geo.Point(-118.2625, 33.73),
                            "366000000");
            sql.update(
                    """
                    INSERT INTO asset_fence_state (asset_id, fence_id, tenant_id, inside,
                            last_transition_position_ts, fence_version)
                    VALUES (366000000, 1, 1, true, 1000, 1)
                    """);
            var state = stub.getAssetStatus(request);
            assertThat(state.getStateLimit()).isEqualTo(50);
            assertThat(state.getPosition().getSog()).isZero();
            assertThat(state.getPosition().hasSog()).isTrue();
            assertThat(state.getPosition().hasCog()).isFalse();
            assertThat(state.getPosition().getHeading()).isEqualTo(90);
            assertThat(state.getConfirmedStatesList())
                    .singleElement()
                    .satisfies(
                            row -> {
                                assertThat(row.getFenceId()).isEqualTo(1);
                                assertThat(row.getInside()).isTrue();
                                assertThat(row.getLastTransitionPositionTs()).isEqualTo(1000);
                                assertThat(row.getFenceVersion()).isEqualTo(1);
                            });
            var inserts = new ArrayList<Object[]>();
            for (int i = 0; i < 60; i++) {
                inserts.add(new Object[] {String.format("%064x", i), i * 1000L});
            }
            sql.batchUpdate(
                    """
                    INSERT INTO geofence_event (event_id, tenant_id, asset_id, fence_id,
                            type, position_ts, fence_version)
                    VALUES (?, 1, 366000000, 1, 'ENTER', ?, 1)
                    """,
                    inserts);
            var events =
                    stub.listEvents(
                            dev.fleet.common.rpc.EventsRequest.newBuilder()
                                    .setTenantId(1)
                                    .setLimit(100)
                                    .build());
            assertThat(events.getLimit()).isEqualTo(50);
            assertThat(events.getEventsCount()).isEqualTo(50);
            assertThat(events.getEvents(0).getPositionTs()).isEqualTo(59000);
            assertThat(events.getEvents(0).getType())
                    .isEqualTo(dev.fleet.common.rpc.GeofenceEvent.Type.ENTER);
            assertThat(
                            stub.listEvents(
                                            dev.fleet.common.rpc.EventsRequest.newBuilder()
                                                    .setTenantId(1)
                                                    .setFromTs(1000)
                                                    .setToTs(2000)
                                                    .build())
                                    .getEventsCount())
                    .isEqualTo(2);
            var nearby =
                    stub.findNearby(
                            dev.fleet.common.rpc.NearbyRequest.newBuilder()
                                    .setTenantId(1)
                                    .setFenceId(1)
                                    .setRadiusM(500)
                                    .setLimit(1)
                                    .build());
            assertThat(nearby.getLimit()).isEqualTo(1);
            assertThat(nearby.getVesselsList())
                    .singleElement()
                    .satisfies(
                            row -> {
                                assertThat(row.getAssetId()).isEqualTo(366000000);
                                assertThat(row.getDistanceM()).isBetween(0.0, 1.0);
                            });
            var fences =
                    stub.listFences(
                            dev.fleet.common.rpc.FencesRequest.newBuilder()
                                    .setTenantId(1)
                                    .setLimit(1)
                                    .build());
            assertThat(fences.getLimit()).isEqualTo(1);
            assertThat(fences.getFencesList())
                    .singleElement()
                    .satisfies(
                            row -> {
                                assertThat(row.getName()).isEqualTo("Los Angeles");
                                assertThat(row.getCenterLat()).isEqualTo(33.73);
                                assertThat(row.getEnterRadiusM()).isEqualTo(2000);
                                assertThat(row.getExitRadiusM()).isEqualTo(3000);
                                assertThat(row.getVersion()).isEqualTo(1);
                            });
            assertThatThrownBy(
                            () ->
                                    stub.listFences(
                                            dev.fleet.common.rpc.FencesRequest.newBuilder()
                                                    .setTenantId(1)
                                                    .setLimit(0)
                                                    .build()))
                    .hasMessageContaining("INVALID_ARGUMENT");
        } finally {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void llmBucketUsesMinuteRefillAndDoesNotSpendOtherTenantOrIngestTokens() {
        assertThat(limiter.acquireLlm(1, 1).allowed()).isTrue();
        var exhausted = limiter.acquireLlm(1, 1);
        assertThat(exhausted.allowed()).isFalse();
        assertThat(exhausted.retryAfterMillis()).isBetween(50000L, 60000L);
        assertThat(limiter.acquireLlm(2, 1).allowed()).isTrue();
        assertThat(limiter.acquire(1, 1000, 1000).allowed()).isTrue();
        assertThat(redis.getExpire("tenant:1:rate:llm", TimeUnit.SECONDS)).isBetween(110L, 120L);
    }

    @Test
    void copilotDatabaseReadsApplyTenantTimeAssetAndFenceFilters() {
        sql.update(
                "INSERT INTO asset (id, tenant_id, mmsi, name) VALUES (366000000, 1, 366000000,"
                        + " 'own'), (366000001, 2, 366000001, 'other')");
        sql.update(
                """
                INSERT INTO geofence_event (event_id, tenant_id, asset_id, fence_id, type, position_ts, fence_version)
                VALUES (?, 1, 366000000, 1, 'ENTER', 1000, 1),
                       (?, 1, 366000000, 1, 'EXIT', 3000, 1),
                       (?, 2, 366000001, 2, 'ENTER', 2000, 1)
                """,
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64));
        sql.update(
                """
                INSERT INTO asset_fence_state (asset_id, fence_id, tenant_id, inside, last_transition_position_ts, fence_version)
                VALUES (366000000, 1, 1, true, 1000, 1), (366000001, 2, 2, true, 2000, 1)
                """);
        assertThat(copilotStore.events(1, 366000000L, 1L, 1000L, 2000L, 50))
                .singleElement()
                .extracting(dev.fleet.api.copilot.CopilotStore.Event::eventId)
                .isEqualTo("a".repeat(64));
        assertThat(copilotStore.events(1, null, null, null, null, 1))
                .singleElement()
                .extracting(dev.fleet.api.copilot.CopilotStore.Event::eventId)
                .isEqualTo("b".repeat(64));
        assertThat(copilotStore.events(1, 366000001L, null, null, null, 50)).isEmpty();
        assertThat(copilotStore.states(1, 366000001)).isEmpty();
        assertThat(copilotStore.states(1, 366000000)).hasSize(1);
    }


    @Test
    void outboxIndexStartsWithOwnedTopic() {
        assertThat(
                        sql.queryForList(
                                """
                                SELECT column_name FROM information_schema.statistics
                                WHERE table_schema = DATABASE() AND table_name = 'outbox'
                                  AND index_name = 'unpublished_rows' ORDER BY seq_in_index
                                """,
                                String.class))
                .containsExactly("topic", "published_at", "id");
    }

    @Test
    void migratesAllTablesAndPublishesCanonicalTenantOwnedRecords() {
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema"
                                    + " = DATABASE() AND table_name IN"
                                    + " ('tenant','asset','fence','asset_fence_state','geofence_event','outbox','replication_heartbeat')",
                                Integer.class))
                .isEqualTo(7);
        long mmsi = 366000000;
        var batch = new IngestBatch(TenantAssignment.forMmsi(mmsi), List.of(point(mmsi)));
        var response = http.postForEntity("/ingest", batch, IngestController.Receipt.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().accepted()).isEqualTo(1);
        assertThat(
                        sql.queryForObject(
                                "SELECT tenant_id FROM asset WHERE mmsi = ?", Long.class, mmsi))
                .isEqualTo(batch.tenantId());
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "api-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        try (var consumer =
                new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.POSITIONS));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            boolean found = false;
            while (!found && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (!record.key().equals(Long.toString(mmsi))) {
                        continue;
                    }
                    var position = Json.read(record.value(), Position.class);
                    assertThat(position.tenantId()).isEqualTo(batch.tenantId());
                    assertThat(position.assetId()).isEqualTo(mmsi);
                    found = true;
                }
            }
            assertThat(found).isTrue();
        }
    }

    @Test
    void rejectsForeignTenantWithoutRegisteringOrSpendingTokens() {
        var response =
                http.postForEntity(
                        "/ingest", new IngestBatch(2, List.of(point(366000000))), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM asset", Integer.class)).isZero();
        assertThat(redis.keys("tenant:*:rate:ingest")).isEmpty();
    }

    @Test
    void rejectsInvalidBatchBeforeSideEffects() {
        var request =
                new HttpEntity<>(
                        "{\"tenantId\":1,\"positions\":[]}",
                        new HttpHeaders() {
                            {
                                setContentType(MediaType.APPLICATION_JSON);
                            }
                        });
        assertThat(http.postForEntity("/ingest", request, String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(redis.keys("*")).isEmpty();
    }

    @Test
    void rateLimitsPositionsAndKeepsTenantsIndependent() {
        sql.update("UPDATE tenant SET ingest_rate_per_sec = 1 WHERE id = 1");
        var batch = new IngestBatch(1, List.of(point(366000000)));
        assertThat(http.postForEntity("/ingest", batch, String.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        // Freeze refill while simulating a clock that has moved backwards.
        redis.opsForHash()
                .put(
                        RedisKeys.ingestBucket(1),
                        "updated_at",
                        Long.toString(System.currentTimeMillis() + 60000));
        var rejected = http.postForEntity("/ingest", batch, String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(limiter.acquire(2, 1, 1).allowed()).isTrue();
        redis.opsForHash()
                .put(
                        RedisKeys.ingestBucket(1),
                        "updated_at",
                        Long.toString(System.currentTimeMillis() - 2000));
        assertThat(limiter.acquire(1, 1, 1).allowed()).isTrue();
    }

    @Test
    void concurrentRequestsCannotSpendTheSameTokens() throws Exception {
        redis.opsForHash()
                .putAll(
                        RedisKeys.ingestBucket(1),
                        Map.of(
                                "tokens",
                                "10",
                                "updated_at",
                                Long.toString(System.currentTimeMillis() + 60000)));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 32; i++) {
                tasks.add(executor.submit(() -> limiter.acquire(1, 10, 1).allowed()));
            }
            int accepted = 0;
            for (var task : tasks) {
                if (task.get(10, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(10);
        }
        assertThat(limiter.acquire(1, 10, 11).retryAfterMillis()).isEqualTo(-1);
    }

    @Test
    void databaseRejectsCrossTenantRelationships() {
        sql.update("INSERT INTO asset (id, tenant_id, mmsi, name) VALUES (123, 1, 366000000, 'a')");
        sql.update(
                "INSERT INTO fence (id, tenant_id, name, center_lat, center_lon, enter_radius_m,"
                        + " exit_radius_m) VALUES (456, 2, 'b', 40, -74, 100, 200)");
        try {
            assertThatThrownBy(
                            () ->
                                    sql.update(
                                            "INSERT INTO asset_fence_state VALUES (123, 456, 1,"
                                                    + " true, 1000, 1)"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            sql.update("DELETE FROM fence WHERE id = 456");
        }
    }

    @Test
    void missingNullOrFractionalRequiredNumbersAreRejectedBeforeSideEffects() {
        String valid = Json.write(new IngestBatch(1, List.of(point(366000000))));
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        for (String invalid :
                List.of(
                        valid.replace("\"lat\":40.66,", ""),
                        valid.replace("\"lat\":40.66", "\"lat\":null"),
                        valid.replace("\"positionTs\":1000", "\"positionTs\":1000.5"))) {
            assertThat(
                            http.postForEntity(
                                            "/ingest",
                                            new HttpEntity<>(invalid, headers),
                                            String.class)
                                    .getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(redis.keys("*")).isEmpty();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM asset", Integer.class)).isZero();
    }

    @Test
    void warmIngestDoesNotQueryMysqlAndOnlyRegistersNewMmsis() {
        var first = new IngestBatch(1, List.of(point(366000000)));
        assertThat(http.postForEntity("/ingest", first, String.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        clearInvocations(tenantMapper, assetMapper);

        assertThat(http.postForEntity("/ingest", first, String.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        verifyNoInteractions(tenantMapper, assetMapper);

        var mixed = new IngestBatch(1, List.of(point(366000000), point(366000003)));
        assertThat(http.postForEntity("/ingest", mixed, String.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        verifyNoInteractions(tenantMapper);
        verify(assetMapper).insertBatch(1, List.of(366000003L));
        verify(assetMapper).findOwned(1, List.of(366000003L));
        verifyNoMoreInteractions(assetMapper);
    }

    @Test
    void rolledBackRegistrationDoesNotWarmOwnershipCache() {
        sql.update(
                "INSERT INTO asset (id, tenant_id, mmsi, name) VALUES (366000000, 2, 366000000,"
                        + " 'existing')");
        assertThatThrownBy(() -> assets.ensureOwned(1, List.of(366000000L, 366000003L)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM asset WHERE mmsi = 366000003", Integer.class))
                .isZero();
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(assets, "confirmedOwners")).isEmpty();

        sql.update("DELETE FROM geofence_event");
        sql.update("DELETE FROM asset_fence_state");
        sql.update("DELETE FROM outbox");
        sql.update("DELETE FROM asset");
        assets.ensureOwned(1, List.of(366000000L, 366000003L));
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM asset", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> assets.ensureOwned(2, List.of(366000000L)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void fenceCrudIsTenantScopedAndWritesVersionedChangeOutbox() {
        var request = new FenceRequest("Demo terminal", 33.73, -118.2625, 2000, 3000);
        var created =
                http.postForEntity("/tenants/1/fences", request, dev.fleet.api.db.FenceRow.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var row = created.getBody();
        assertThat(row.version()).isEqualTo(1);
        String endpoint = "/tenants/1/fences/" + row.id();
        var wrongTenant =
                http.exchange(
                        "/tenants/2/fences/" + row.id(),
                        HttpMethod.PUT,
                        new HttpEntity<>(request),
                        String.class);
        assertThat(wrongTenant.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var updated =
                http.exchange(
                        endpoint,
                        HttpMethod.PUT,
                        new HttpEntity<>(request),
                        dev.fleet.api.db.FenceRow.class);
        assertThat(updated.getBody().version()).isEqualTo(2);
        var deleted = http.exchange(endpoint, HttpMethod.DELETE, HttpEntity.EMPTY, String.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(
                        sql.queryForObject(
                                "SELECT version FROM fence WHERE id = ?", Long.class, row.id()))
                .isEqualTo(3);
        assertThat(
                        sql.queryForObject(
                                "SELECT deleted FROM fence WHERE id = ?", Boolean.class, row.id()))
                .isTrue();
        var messages =
                sql.queryForList(
                        "SELECT CAST(payload AS CHAR) FROM outbox WHERE topic = 'fence-changes'"
                                + " ORDER BY id",
                        String.class);
        assertThat(
                        messages.stream()
                                .map(payload -> Json.read(payload, FenceChange.class).version()))
                .containsExactly(1L, 2L, 3L);
        assertThat(Json.read(messages.getLast(), FenceChange.class).deleted()).isTrue();
    }

    @Test
    void outboxInsertFailureRollsBackFenceCreateUpdateAndDelete() {
        var request = new FenceRequest("Rollback terminal", 33.73, -118.2625, 2000, 3000);
        var created =
                http.postForEntity("/tenants/1/fences", request, dev.fleet.api.db.FenceRow.class)
                        .getBody();
        long before = sql.queryForObject("SELECT COUNT(*) FROM fence", Long.class);
        sql.execute(
                "CREATE TRIGGER outbox_test_fail BEFORE INSERT ON outbox FOR EACH ROW SIGNAL"
                        + " SQLSTATE '45000' SET MESSAGE_TEXT = 'test outbox failure'");
        try {
            assertThat(
                            http.postForEntity("/tenants/1/fences", request, String.class)
                                    .getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM fence", Long.class))
                    .isEqualTo(before);
            String endpoint = "/tenants/1/fences/" + created.id();
            assertThat(
                            http.exchange(
                                            endpoint,
                                            HttpMethod.PUT,
                                            new HttpEntity<>(request),
                                            String.class)
                                    .getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(
                            http.exchange(
                                            endpoint,
                                            HttpMethod.DELETE,
                                            HttpEntity.EMPTY,
                                            String.class)
                                    .getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(
                            sql.queryForObject(
                                    "SELECT version FROM fence WHERE id = ?",
                                    Long.class,
                                    created.id()))
                    .isEqualTo(1);
            assertThat(
                            sql.queryForObject(
                                    "SELECT deleted FROM fence WHERE id = ?",
                                    Boolean.class,
                                    created.id()))
                    .isFalse();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class))
                    .isEqualTo(1);
        } finally {
            sql.execute("DROP TRIGGER outbox_test_fail");
        }
    }
}
