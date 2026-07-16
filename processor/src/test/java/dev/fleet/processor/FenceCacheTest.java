package dev.fleet.processor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.fleet.common.FenceChange;
import dev.fleet.processor.geofence.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;

class FenceCacheTest {
    @Test
    void ignoresStaleBroadcastsAndReconcilesMissedUpdateAndDeletion() {
        var mapper = mock(FenceMapper.class);
        var index = new FenceIndex();
        index.put(new Fence(1, 1, "LA", 33.73, -118.2625, 2000, 3000, 1));
        var metrics = new SimpleMeterRegistry();
        var cache = new FenceCache(mapper, index, metrics);
        cache.changed(new FenceChange(1, 1, 1, false));
        verifyNoInteractions(mapper);
        when(mapper.revisions()).thenReturn(List.of(new FenceRevision(1, 2, false)));
        when(mapper.find(1))
                .thenReturn(new FenceRow(1, 1, "LA", 33.73, -118.2625, 2500, 3500, 2, false));
        cache.reconcile();
        assertThat(index.get(1).version()).isEqualTo(2);
        cache.changed(new FenceChange(1, 1, 1, false));
        verify(mapper, times(1)).find(1);
        when(mapper.revisions()).thenReturn(List.of(new FenceRevision(1, 3, true)));
        when(mapper.find(1))
                .thenReturn(new FenceRow(1, 1, "LA", 33.73, -118.2625, 2500, 3500, 3, true));
        cache.reconcile();
        assertThat(index.get(1)).isNull();
        assertThat(metrics.get("fence.cache.repairs").counter().count()).isEqualTo(2);
        cache.changed(new FenceChange(1, 1, 2, false));
        verify(mapper, times(2)).find(1);
    }
}
