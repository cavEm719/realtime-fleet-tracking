package dev.fleet.processor;

import static org.assertj.core.api.Assertions.*;

import dev.fleet.processor.geofence.*;

import org.junit.jupiter.api.Test;

class FenceIndexTest {
    @Test
    void lookupIsTenantScoped() {
        var index = new FenceIndex();
        var fence = new Fence(1, 1, "Port", 0, 0, 1000, 2000, 1);
        index.put(fence);
        assertThat(index.forTenant(1)).containsExactly(fence);
        assertThat(index.forTenant(2)).isEmpty();
    }

    @Test
    void singleFenceCanBeReplacedAndRemovedWithoutDisturbingAnotherFence() {
        var index = new FenceIndex();
        var first = new Fence(1, 1, "First", 0, 0, 1000, 2000, 1);
        var second = new Fence(2, 1, "Second", 0, 0, 1000, 2000, 1);
        index.put(first);
        index.put(second);
        var moved = new Fence(1, 2, "Moved", 10, 10, 1000, 2000, 2);
        index.put(moved);
        assertThat(index.forTenant(1)).containsExactly(second);
        assertThat(index.forTenant(2)).containsExactly(moved);
        index.remove(1);
        assertThat(index.forTenant(2)).isEmpty();
        assertThat(index.get(1)).isNull();
        assertThat(index.forTenant(1)).containsExactly(second);
    }

    @Test
    void readersKeepTheirSnapshotWhileWritersReplaceIt() {
        var index = new FenceIndex();
        var fence = new Fence(1, 1, "Port", 0, 0, 1000, 2000, 1);
        index.put(fence);
        var seen = index.forTenant(1);
        index.remove(1);
        assertThat(seen).containsExactly(fence);
        assertThat(index.forTenant(1)).isEmpty();
    }
}
