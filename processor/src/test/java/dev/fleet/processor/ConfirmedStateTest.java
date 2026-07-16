package dev.fleet.processor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.fleet.common.*;
import dev.fleet.processor.geofence.*;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

class ConfirmedStateTest {
    private final TopicPartition partition = new TopicPartition(Topics.POSITIONS, 0);

    private PartitionPosition point(long asset, long ts) {
        return new PartitionPosition(
                partition,
                new Position(1, asset, asset, ts, 33.73, -118.2625, null, null, null, ts));
    }

    private FenceIndex index() {
        var index = new FenceIndex();
        index.put(new Fence(1, 1, "LA", 33.73, -118.2625, 2000, 3000, 1));
        return index;
    }

    @Test
    void loadsAllUnseenAssetsOnceAndDoesNotReenterAtLaterConfirmationTimestamp() {
        var loader = mock(ConfirmedStateLoader.class);
        var refs = List.of(new AssetRef(1, 366000000), new AssetRef(1, 366000003));
        when(loader.load(refs))
                .thenReturn(
                        List.of(
                                new ConfirmedState(1, 366000000, 1, true, 121_000, 1),
                                new ConfirmedState(1, 366000003, 1, true, 121_000, 1)));
        var engine =
                new GeofenceEngine(
                        index(),
                        new GeofenceSettings(
                                GeofenceSettings.Mode.HYSTERESIS, 3, Duration.ofSeconds(120)),
                        loader);
        assertThat(
                        engine.processBatch(
                                List.of(
                                        point(366000000, 181_000),
                                        point(366000003, 181_000),
                                        point(366000000, 241_000),
                                        point(366000000, 301_000))))
                .isEmpty();
        engine.processBatch(List.of(point(366000003, 361_000)));
        verify(loader).load(refs);
        verifyNoMoreInteractions(loader);
        engine.dropPartitions(List.of(partition));
        engine.processBatch(List.of(point(366000000, 421_000)));
        verify(loader).load(List.of(new AssetRef(1, 366000000)));
    }

    @Test
    void deletedFenceDropsInsideStateWithoutAnExit() {
        var index = index();
        var engine =
                new GeofenceEngine(
                        index,
                        new GeofenceSettings(
                                GeofenceSettings.Mode.NAIVE, 3, Duration.ofSeconds(120)),
                        refs -> List.of(new ConfirmedState(1, 366000000, 1, true, 1000, 1)));
        assertThat(engine.processBatch(List.of(point(366000000, 2000)))).isEmpty();
        index.remove(1);
        var outside = new Position(1, 366000000, 366000000, 3000, 34, -118, null, null, null, 3000);
        assertThat(engine.processBatch(List.of(new PartitionPosition(partition, outside))))
                .isEmpty();
    }

    @Test
    void eventIdentityIsStableAcrossModeAndVersionButChangesWithTransitionTime() {
        var first =
                new Transition(
                        1,
                        366000000,
                        1,
                        1,
                        1000,
                        Transition.Type.ENTER,
                        GeofenceSettings.Mode.NAIVE);
        var second =
                new Transition(
                        1,
                        366000000,
                        1,
                        2,
                        1000,
                        Transition.Type.ENTER,
                        GeofenceSettings.Mode.HYSTERESIS);
        assertThat(first.eventId()).hasSize(64).isEqualTo(second.eventId());
        assertThat(
                        new Transition(
                                        1,
                                        366000000,
                                        1,
                                        1,
                                        2000,
                                        Transition.Type.ENTER,
                                        GeofenceSettings.Mode.NAIVE)
                                .eventId())
                .isNotEqualTo(first.eventId());
    }
}
