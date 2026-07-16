package dev.fleet.processor;

import static org.assertj.core.api.Assertions.*;

import dev.fleet.common.*;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

import java.util.*;
import java.util.concurrent.*;

@Testcontainers
class PositionStoreIT {
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.9")).withExposedPorts(6379);

    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private static RedisPositionStore store;

    @BeforeAll
    static void connect() {
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet();
        connection.start();
        redis = new StringRedisTemplate(connection);
        store = new RedisPositionStore(redis);
    }

    @AfterAll
    static void close() {
        connection.destroy();
    }

    @BeforeEach
    void reset() {
        try (var c = connection.getConnection()) {
            c.serverCommands().flushDb();
        }
    }

    private Position point(long mmsi, long ts) {
        return new Position(
                TenantAssignment.forMmsi(mmsi),
                mmsi,
                mmsi,
                ts,
                40.660123456789123,
                -74.05012345678912,
                8.0,
                90.0,
                null,
                System.currentTimeMillis());
    }

    @Test
    void acceptsStrictlyIncreasingTimestampsIncludingDuplicatesInOneBatch() {
        var p = point(366000000, 1000);
        var newer = point(366000000, 2000);
        assertThat(store.apply(List.of(p, p, point(366000000, 999), newer, point(366000000, 1500))))
                .containsExactly(true, false, false, true, false);
        assertThat(store.apply(List.of(p, newer))).containsExactly(false, false);
        assertThat(redis.opsForHash().get(RedisKeys.latest(1, p.assetId()), "position_ts"))
                .isEqualTo("2000");
        String payload =
                (String) redis.opsForHash().get(RedisKeys.latest(1, p.assetId()), "position");
        assertThat(Json.read(payload, Position.class)).isEqualTo(newer);
        assertThat(redis.opsForGeo().position(RedisKeys.geo(1), Long.toString(p.assetId())))
                .hasSize(1);
    }

    @Test
    void keepsTenantsAndGeoMembershipSeparate() {
        var a = point(366000000, 1000);
        var b = point(366000001, 1000);
        assertThat(store.apply(List.of(a, b))).containsExactly(true, true);
        assertThat(redis.opsForZSet().range(RedisKeys.geo(1), 0, -1))
                .containsExactly(Long.toString(a.assetId()));
        assertThat(redis.opsForZSet().range(RedisKeys.geo(2), 0, -1))
                .containsExactly(Long.toString(b.assetId()));
        assertThat(redis.hasKey(RedisKeys.latest(1, b.assetId()))).isFalse();
    }

    @Test
    void emitsOnlyAcceptedUpdates() throws Exception {
        var received = new LinkedBlockingQueue<String>();
        var subscriber = new RedisMessageListenerContainer();
        subscriber.setConnectionFactory(connection);
        subscriber.addMessageListener(
                (message, pattern) ->
                        received.add(
                                new String(
                                        message.getBody(),
                                        java.nio.charset.StandardCharsets.UTF_8)),
                new org.springframework.data.redis.listener.ChannelTopic(RedisKeys.channel(1)));
        subscriber.afterPropertiesSet();
        subscriber.start();
        try {
            var p = point(366000000, 1000);
            store.apply(List.of(p, p));
            assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo(Json.write(p));
            assertThat(received.poll(200, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            subscriber.stop();
            subscriber.destroy();
        }
    }

    @Test
    void retryAfterUnknownResultChangesNoStateAndPublishesNoDuplicate() {
        var p = point(366000000, 0);
        assertThat(store.apply(List.of(p))).containsExactly(true);
        // A new store represents a processor restart with no local state.
        assertThat(new RedisPositionStore(redis).apply(List.of(p))).containsExactly(false);
        assertThat(redis.opsForHash().get(RedisKeys.latest(1, p.assetId()), "position"))
                .isEqualTo(Json.write(p));
    }

    @Test
    void concurrentBatchesCannotOverwriteTheHighestTimestampWithAnOlderOne() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (long ts = 100; ts >= 1; ts--) {
                long timestamp = ts;
                tasks.add(executor.submit(() -> store.apply(List.of(point(366000000, timestamp)))));
            }
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }
        assertThat(redis.opsForHash().get(RedisKeys.latest(1, 366000000), "position_ts"))
                .isEqualTo("100");
        var saved =
                Json.read(
                        (String) redis.opsForHash().get(RedisKeys.latest(1, 366000000), "position"),
                        Position.class);
        assertThat(saved.positionTs()).isEqualTo(100);
    }
}
