package dev.fleet.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import dev.fleet.common.*;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Testcontainers
class GatewayBroadcastIT {
    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.9")).withExposedPorts(6379);

    private static ConfigurableApplicationContext first;
    private static ConfigurableApplicationContext second;
    private static DefaultKafkaProducerFactory<String, String> producers;
    private static KafkaTemplate<String, String> kafka;
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void startInstances() throws Exception {
        try (var admin =
                Admin.create(
                        Map.of(
                                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(Topics.FENCE_EVENTS, 12, (short) 1)))
                    .all()
                    .get(15, TimeUnit.SECONDS);
        }
        first = start();
        second = start();
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
        connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
        await().atMost(Duration.ofSeconds(20)).until(() -> assigned(first) && assigned(second));
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(GatewayApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        "--gateway.event-group-id=gateway-instance-" + UUID.randomUUID(),
                        "--logging.level.root=WARN");
    }

    private static boolean assigned(ConfigurableApplicationContext context) {
        var container =
                context.getBean(KafkaListenerEndpointRegistry.class)
                        .getListenerContainer("gateway-fence-events");
        return container.getAssignedPartitions() != null
                && container.getAssignedPartitions().size() == 12;
    }

    @AfterAll
    static void stopInstances() {
        if (first != null) {
            first.close();
        }
        if (second != null) {
            second.close();
        }
        if (producers != null) {
            producers.destroy();
        }
        if (connections != null) {
            connections.destroy();
        }
    }

    private record Frame(
            String type, List<Position> positions, GeofenceEvent event, String message) {}

    private static class Client implements WebSocket.Listener, AutoCloseable {
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();
        private final HttpClient http = HttpClient.newHttpClient();
        private WebSocket socket;

        Client(ConfigurableApplicationContext context, long tenantId) throws Exception {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            socket =
                    http.newWebSocketBuilder()
                            .buildAsync(
                                    URI.create(
                                            "ws://localhost:"
                                                    + port
                                                    + "/ws?tenantId="
                                                    + tenantId
                                                    + "&userId=test"),
                                    this)
                            .get(10, TimeUnit.SECONDS);
        }

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(Long.MAX_VALUE);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                frames.add(partial.toString());
                partial.setLength(0);
            }
            return CompletableFuture.completedFuture(null);
        }

        void command(String payload) {
            socket.sendText(payload, true).join();
        }

        Frame receive(String type) throws Exception {
            String payload = frames.poll(10, TimeUnit.SECONDS);
            assertThat(payload).as("received %s frame", type).isNotNull();
            var frame = Json.read(payload, Frame.class);
            assertThat(frame.type()).isEqualTo(type);
            return frame;
        }

        @Override
        public void close() {
            if (socket != null) {
                socket.abort();
            }
            http.close();
        }
    }

    @Test
    void bothGatewayInstancesDeliverTheSameFenceEventToTheirOwnClients() throws Exception {
        try (var one = new Client(first, 1);
                var two = new Client(second, 1)) {
            one.command("{\"type\":\"subscribe\",\"all\":true}");
            two.command("{\"type\":\"subscribe\",\"all\":true}");
            one.receive("subscribed");
            two.receive("subscribed");
            var event =
                    new GeofenceEvent(
                            "a".repeat(64), 1, 366000000, 1, 1, 3000, GeofenceEvent.Type.EXIT);
            kafka.send(Topics.FENCE_EVENTS, "366000000", Json.write(event))
                    .get(10, TimeUnit.SECONDS);
            assertThat(one.receive("fence-event").event()).isEqualTo(event);
            assertThat(two.receive("fence-event").event()).isEqualTo(event);
            kafka.send(Topics.FENCE_EVENTS, "366000000", Json.write(event))
                    .get(10, TimeUnit.SECONDS);
            var older =
                    new GeofenceEvent(
                            "b".repeat(64), 1, 366000000, 1, 1, 2000, GeofenceEvent.Type.ENTER);
            kafka.send(Topics.FENCE_EVENTS, "366000000", Json.write(older))
                    .get(10, TimeUnit.SECONDS);
            var newer =
                    new GeofenceEvent(
                            "c".repeat(64), 1, 366000000, 1, 1, 4000, GeofenceEvent.Type.ENTER);
            kafka.send(Topics.FENCE_EVENTS, "366000000", Json.write(newer))
                    .get(10, TimeUnit.SECONDS);
            assertThat(one.receive("fence-event").event()).isEqualTo(newer);
            assertThat(two.receive("fence-event").event()).isEqualTo(newer);
        }
    }

    @Test
    void redisChannelBroadcastReachesBothInstancesAndRejectsCrossTenantSubscriptions()
            throws Exception {
        try (var one = new Client(first, 1);
                var two = new Client(second, 1);
                var other = new Client(first, 2)) {
            one.command("{\"type\":\"subscribe\",\"assetIds\":[366000000]}");
            two.command("{\"type\":\"subscribe\",\"all\":true}");
            other.command("{\"type\":\"subscribe\",\"assetIds\":[366000000]}");
            one.receive("subscribed");
            two.receive("subscribed");
            assertThat(other.receive("error").message()).contains("another tenant");
            var point =
                    new Position(
                            1,
                            366000000,
                            366000000,
                            1000,
                            33.73,
                            -118.2625,
                            null,
                            null,
                            null,
                            System.currentTimeMillis());
            redis.convertAndSend(RedisKeys.channel(1), Json.write(point));
            assertThat(one.receive("positions").positions()).containsExactly(point);
            assertThat(two.receive("positions").positions()).containsExactly(point);
            assertThat(other.frames.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void onlyLocallyActiveTenantsHaveRedisSubscribersAndLastCloseRemovesThem() throws Exception {
        await().atMost(Duration.ofSeconds(5))
                .until(
                        () ->
                                !first.getBean(GatewayHub.class).hasTenant(1)
                                        && !first.getBean(GatewayHub.class).hasTenant(2)
                                        && !second.getBean(GatewayHub.class).hasTenant(1)
                                        && !second.getBean(GatewayHub.class).hasTenant(2));
        try (var client = new Client(first, 1)) {
            client.command("{\"type\":\"subscribe\",\"all\":true}");
            client.receive("subscribed");
            var other =
                    new Position(
                            2,
                            366000001,
                            366000001,
                            9000,
                            33.7,
                            -118.2,
                            null,
                            null,
                            null,
                            System.currentTimeMillis());
            // Redis's publish count includes matching pattern subscribers too: zero proves
            // the tenant-2 payload did not reach either gateway's Redis listener.
            assertThat(redis.convertAndSend(RedisKeys.channel(2), Json.write(other))).isZero();
            var own =
                    new Position(
                            1,
                            366000006,
                            366000006,
                            9000,
                            33.7,
                            -118.2,
                            null,
                            null,
                            null,
                            System.currentTimeMillis());
            assertThat(redis.convertAndSend(RedisKeys.channel(1), Json.write(own))).isEqualTo(1);
            assertThat(client.receive("positions").positions()).containsExactly(own);
            assertThat(client.frames.poll(200, TimeUnit.MILLISECONDS)).isNull();
        }
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(redis.convertAndSend(RedisKeys.channel(1), "{}"))
                                        .isZero());
    }

    @Test
    void handshakeRejectsUnknownTenantBeforeOpeningASession() throws Exception {
        int port = ((ServletWebServerApplicationContext) first).getWebServer().getPort();
        try (var http = HttpClient.newHttpClient()) {
            assertThatThrownBy(
                            () ->
                                    http.newWebSocketBuilder()
                                            .buildAsync(
                                                    URI.create(
                                                            "ws://localhost:"
                                                                    + port
                                                                    + "/ws?tenantId=99&userId=test"),
                                                    new WebSocket.Listener() {})
                                            .join())
                    .hasCauseInstanceOf(WebSocketHandshakeException.class);
        }
    }
}
