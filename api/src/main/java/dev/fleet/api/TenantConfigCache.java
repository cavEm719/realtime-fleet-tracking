package dev.fleet.api;

import dev.fleet.api.db.TenantMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Component
public class TenantConfigCache {
    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final TenantMapper tenants;
    private final LongSupplier clock;
    private final ConcurrentMap<Long, CachedConfig> cache = new ConcurrentHashMap<>();

    public record TenantConfig(long id, int ingestRatePerSec, int llmRatePerMin) {}

    private record CachedConfig(TenantConfig config, long loadedAt) {}

    @Autowired
    public TenantConfigCache(TenantMapper tenants) {
        this(tenants, System::nanoTime);
    }

    TenantConfigCache(TenantMapper tenants, LongSupplier clock) {
        this.tenants = tenants;
        this.clock = clock;
    }

    public TenantConfig get(long tenantId) {
        // A valid hit avoids compute's bin lock on the shared tenant hot path.
        var current = cache.get(tenantId);
        if (current != null && clock.getAsLong() - current.loadedAt() < TTL_NANOS) {
            return current.config();
        }
        return cache.compute(
                        tenantId,
                        (id, cached) -> {
                            if (cached != null
                                    && clock.getAsLong() - cached.loadedAt() < TTL_NANOS) {
                                return cached;
                            }
                            var tenant = tenants.selectById(id);
                            if (tenant == null) {
                                throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Unknown tenant");
                            }
                            var config =
                                    new TenantConfig(
                                            tenant.getId(),
                                            tenant.getIngestRatePerSec(),
                                            tenant.getLlmRatePerMin());
                            return new CachedConfig(config, clock.getAsLong());
                        })
                .config();
    }
}
