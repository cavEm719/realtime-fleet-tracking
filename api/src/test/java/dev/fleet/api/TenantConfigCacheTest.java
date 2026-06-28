package dev.fleet.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.fleet.api.db.TenantEntity;
import dev.fleet.api.db.TenantMapper;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

class TenantConfigCacheTest {
    @Test
    void configurationIsReusedForThirtySecondsThenReloaded() {
        var mapper = mock(TenantMapper.class);
        var clock = new AtomicLong();
        var cache = new TenantConfigCache(mapper, clock::get);
        when(mapper.selectById(1L)).thenReturn(tenant(1000), tenant(10));

        assertThat(cache.get(1).ingestRatePerSec()).isEqualTo(1000);
        clock.set(TimeUnit.SECONDS.toNanos(29));
        assertThat(cache.get(1).ingestRatePerSec()).isEqualTo(1000);
        verify(mapper).selectById(1L);

        clock.set(TimeUnit.SECONDS.toNanos(30));
        assertThat(cache.get(1).ingestRatePerSec()).isEqualTo(10);
        verify(mapper, times(2)).selectById(1L);
    }

    @Test
    void missingTenantsAndDatabaseFailuresAreNotCached() {
        var mapper = mock(TenantMapper.class);
        var cache = new TenantConfigCache(mapper);
        when(mapper.selectById(1L))
                .thenReturn(null)
                .thenThrow(new DataAccessResourceFailureException("MySQL unavailable"))
                .thenReturn(tenant(1000));

        assertThatThrownBy(() -> cache.get(1)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> cache.get(1))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(cache.get(1).ingestRatePerSec()).isEqualTo(1000);
        verify(mapper, times(3)).selectById(1L);
    }

    private TenantEntity tenant(int rate) {
        var tenant = new TenantEntity();
        tenant.setId(1L);
        tenant.setIngestRatePerSec(rate);
        tenant.setLlmRatePerMin(60);
        return tenant;
    }
}
