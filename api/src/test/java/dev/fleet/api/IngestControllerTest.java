package dev.fleet.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.fleet.common.IngestBatch;
import dev.fleet.common.IngestPoint;
import dev.fleet.common.Topics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class IngestControllerTest {
    private KafkaTemplate<String, String> kafka;
    private RateLimiter limiter;
    private IngestController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void configureController() {
        var tenants = mock(TenantConfigCache.class);
        var assets = mock(AssetRegistry.class);
        kafka = mock(KafkaTemplate.class);
        limiter = mock(RateLimiter.class);
        when(tenants.get(1)).thenReturn(new TenantConfigCache.TenantConfig(1, 1000, 60));
        when(limiter.acquire(1, 1000, 2)).thenReturn(new RateLimiter.Decision(true, 0));
        controller =
                new IngestController(tenants, assets, limiter, kafka, new SimpleMeterRegistry());
    }

    @Test
    void enqueuesTheWholeBatchBeforeWaitingAndAcceptsOnlyAfterAllAcknowledgements()
            throws Exception {
        var first = new CompletableFuture<SendResult<String, String>>();
        var last = new CompletableFuture<SendResult<String, String>>();
        var calls = new AtomicInteger();
        var enqueued = new CountDownLatch(2);
        when(kafka.send(eq(Topics.POSITIONS), eq("366000000"), anyString()))
                .thenAnswer(
                        invocation -> {
                            var position =
                                    dev.fleet.common.Json.read(
                                            invocation.getArgument(2),
                                            dev.fleet.common.Position.class);
                            assertThat(position.ingestedAt()).isEqualTo(1234);
                            int index = calls.getAndIncrement();
                            enqueued.countDown();
                            return index == 0 ? first : last;
                        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var response = executor.submit(() -> controller.ingest(batch(), 1234));
            try {
                assertThat(enqueued.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(response.isDone()).isFalse();
                first.complete(null);
                assertThat(response.isDone()).isFalse();
                last.complete(null);
                var accepted = response.get(2, TimeUnit.SECONDS);
                assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
                assertThat(accepted.getBody().accepted()).isEqualTo(2);
            } finally {
                // Unblock shutdown even if an assertion fails while acknowledgements are pending.
                first.complete(null);
                last.complete(null);
            }
        }
    }

    @Test
    void anyFailedAcknowledgementReturnsUncertainAcceptanceForTheWholeBatch() {
        when(kafka.send(eq(Topics.POSITIONS), eq("366000000"), anyString()))
                .thenReturn(
                        CompletableFuture.failedFuture(new KafkaException("Broker unavailable")))
                .thenReturn(CompletableFuture.completedFuture(null));
        assertThatThrownBy(() -> controller.ingest(batch(), 1234))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode())
                                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                            assertThat(error.getReason()).contains("Retry the whole batch");
                        });
        verify(limiter).acquire(1, 1000, 2);
    }

    private IngestBatch batch() {
        return new IngestBatch(1, List.of(point(1000), point(2000)));
    }

    private IngestPoint point(long timestamp) {
        return new IngestPoint(
                366000000, timestamp, 40.66, -74.05, null, null, null, System.currentTimeMillis());
    }
}
