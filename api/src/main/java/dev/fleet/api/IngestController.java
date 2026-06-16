package dev.fleet.api;

import dev.fleet.common.IngestBatch;
import dev.fleet.common.IngestPoint;
import dev.fleet.common.Json;
import dev.fleet.common.Position;
import dev.fleet.common.TenantAssignment;
import dev.fleet.common.Topics;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
public class IngestController {
    private final TenantConfigCache tenants;
    private final AssetRegistry assets;
    private final RateLimiter limiter;
    private final KafkaTemplate<String, String> kafka;
    private final MeterRegistry metrics;

    public IngestController(
            TenantConfigCache tenants,
            AssetRegistry assets,
            RateLimiter limiter,
            KafkaTemplate<String, String> kafka,
            MeterRegistry metrics) {
        this.tenants = tenants;
        this.assets = assets;
        this.limiter = limiter;
        this.kafka = kafka;
        this.metrics = metrics;
    }

    public record Receipt(int accepted) {}

    @PostMapping("/ingest")
    public ResponseEntity<Receipt> ingest(
            @RequestBody IngestBatch batch,
            @org.springframework.web.bind.annotation.RequestAttribute(IngestTimingFilter.ATTRIBUTE)
                    long ingestedAt) {
        var tenant = tenants.get(batch.tenantId());
        List<Long> mmsis =
                batch.positions().stream().map(IngestPoint::mmsi).distinct().sorted().toList();
        if (mmsis.stream().anyMatch(mmsi -> TenantAssignment.forMmsi(mmsi) != batch.tenantId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "MMSI assigned to another tenant");
        }
        var decision =
                limiter.acquire(
                        batch.tenantId(), tenant.ingestRatePerSec(), batch.positions().size());
        if (!decision.allowed()) {
            throw rateLimited(decision);
        }
        assets.ensureOwned(batch.tenantId(), mmsis);
        publishBatch(batch, ingestedAt);
        metrics.counter("ingest.positions.acknowledged").increment(batch.positions().size());
        return ResponseEntity.accepted().body(new Receipt(batch.positions().size()));
    }

    private void publishBatch(IngestBatch batch, long ingestedAt) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        var futures =
                new ArrayList<CompletableFuture<SendResult<String, String>>>(
                        batch.positions().size());
        try {
            for (var point : batch.positions()) {
                if (System.nanoTime() >= deadline) {
                    throw new TimeoutException("Batch send deadline exceeded");
                }
                var position = Position.of(batch.tenantId(), point.mmsi(), point, ingestedAt);
                futures.add(
                        kafka.send(
                                Topics.POSITIONS,
                                Long.toString(point.mmsi()),
                                Json.write(position)));
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("Batch acknowledgement deadline exceeded");
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw uncertainAcceptance(error);
        } catch (Exception error) {
            throw uncertainAcceptance(error);
        }
    }

    private ResponseStatusException uncertainAcceptance(Exception error) {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Retry the whole batch; Kafka acceptance is uncertain",
                error);
    }

    private ResponseStatusException rateLimited(RateLimiter.Decision decision) {
        metrics.counter("ingest.rate.rejected").increment();
        if (decision.retryAfterMillis() < 0) {
            return new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Batch exceeds tenant burst capacity; split it");
        }
        return new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "Tenant ingest rate exceeded", null) {
            @Override
            public HttpHeaders getHeaders() {
                var headers = new HttpHeaders();
                long seconds = Math.max(1, (decision.retryAfterMillis() + 999) / 1000);
                headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
                return headers;
            }
        };
    }
}
