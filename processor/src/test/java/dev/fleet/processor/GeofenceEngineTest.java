package dev.fleet.processor;

import static org.assertj.core.api.Assertions.*;

import dev.fleet.common.Position;
import dev.fleet.common.Topics;
import dev.fleet.processor.geofence.*;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

class GeofenceEngineTest {
    private static final TopicPartition PARTITION = new TopicPartition(Topics.POSITIONS, 0);
    private final List<Transition> transitions = new ArrayList<>();
    private final FenceIndex index = new FenceIndex();

    private GeofenceEngine engine(GeofenceSettings.Mode mode) {
        index.put(new Fence(1, 1, "Los Angeles", 33.73, -118.2625, 2000, 3000, 1));
        return new GeofenceEngine(
                index,
                new GeofenceSettings(mode, 3, Duration.ofSeconds(120)),
                assets -> List.of());
    }

    private PartitionPosition point(TopicPartition partition, long ts, double distance) {
        double latitude = 33.73 + Math.toDegrees(distance / 6_371_000);
        return new PartitionPosition(
                partition,
                new Position(
                        1, 366000000, 366000000, ts, latitude, -118.2625, null, null, null, ts));
    }

    private void track(GeofenceEngine engine, double... distances) {
        var batch = new ArrayList<PartitionPosition>();
        for (int i = 0; i < distances.length; i++) {
            batch.add(point(PARTITION, i * 60_000L, distances[i]));
        }
        transitions.addAll(engine.processBatch(batch));
    }

    @Test
    void jitterCreatesNoHysteresisTransitionsButSeveralNaiveTransitions() {
        track(engine(GeofenceSettings.Mode.HYSTERESIS), 4000, 1900, 2100, 1900, 2100, 1900, 2100);
        assertThat(transitions).isEmpty();
        track(engine(GeofenceSettings.Mode.NAIVE), 4000, 1900, 2100, 1900, 2100, 1900, 2100);
        assertThat(transitions)
                .extracting(Transition::type)
                .containsExactly(
                        Transition.Type.ENTER,
                        Transition.Type.EXIT,
                        Transition.Type.ENTER,
                        Transition.Type.EXIT,
                        Transition.Type.ENTER,
                        Transition.Type.EXIT);
    }

    @Test
    void cleanEntryAndExitConfirmOnceEvenAfterJumpingFarAway() {
        track(
                engine(GeofenceSettings.Mode.HYSTERESIS),
                4000,
                1500,
                1000,
                500,
                400,
                2500,
                10_000,
                11_000,
                12_000,
                13_000);
        assertThat(transitions)
                .extracting(Transition::type)
                .containsExactly(Transition.Type.ENTER, Transition.Type.EXIT);
        assertThat(transitions)
                .extracting(Transition::positionTs)
                .containsExactly(180_000L, 480_000L);
    }

    @Test
    void confirmedTransitionCarriesTheConfirmingPointsDeliveryTimestamps() {
        var engine = engine(GeofenceSettings.Mode.NAIVE);
        var point =
                new Position(
                        1, 366000000, 366000000, 1000, 33.73, -118.2625, null, null, null, 2000,
                        3000);
        assertThat(engine.processBatch(List.of(new PartitionPosition(PARTITION, point))))
                .singleElement()
                .satisfies(
                        transition -> {
                            assertThat(transition.emittedAt()).isEqualTo(2000);
                            assertThat(transition.ingestedAt()).isEqualTo(3000);
                        });
    }

    @Test
    void pointCountAndEventTimeAreBothRequiredAcrossBatches() {
        var engine = engine(GeofenceSettings.Mode.HYSTERESIS);
        transitions.addAll(
                engine.processBatch(
                        List.of(
                                point(PARTITION, 0, 1000),
                                point(PARTITION, 1, 1000),
                                point(PARTITION, 2, 1000))));
        assertThat(transitions).isEmpty();
        transitions.addAll(engine.processBatch(List.of(point(PARTITION, 120_000, 1000))));
        assertThat(transitions).extracting(Transition::type).containsExactly(Transition.Type.ENTER);
    }

    @Test
    void duplicatesAndOutOfOrderPositionsDoNotResetOrAdvanceCandidates() {
        var engine = engine(GeofenceSettings.Mode.HYSTERESIS);
        transitions.addAll(
                engine.processBatch(
                        List.of(
                                point(PARTITION, 1000, 1000),
                                point(PARTITION, 61_000, 1000),
                                point(PARTITION, 60_000, 4000),
                                point(PARTITION, 61_000, 4000),
                                point(PARTITION, 121_000, 1000))));
        assertThat(transitions).extracting(Transition::type).containsExactly(Transition.Type.ENTER);
    }

    @Test
    void exitJitterAndCancelledEntryResetCandidates() {
        track(
                engine(GeofenceSettings.Mode.HYSTERESIS),
                1000,
                1000,
                2500,
                1000,
                1000,
                1000,
                3100,
                2900,
                3100,
                2900,
                3100,
                3100,
                3100);
        assertThat(transitions)
                .extracting(Transition::type)
                .containsExactly(Transition.Type.ENTER, Transition.Type.EXIT);
        assertThat(transitions)
                .extracting(Transition::positionTs)
                .containsExactly(300_000L, 720_000L);
    }

    @Test
    void revokeDropsTimestampCandidateAndConfirmedStateOnlyForRevokedPartition() {
        var engine = engine(GeofenceSettings.Mode.HYSTERESIS);
        var other = new TopicPartition(Topics.POSITIONS, 1);
        transitions.addAll(
                engine.processBatch(
                        List.of(
                                point(PARTITION, 1000, 1000),
                                point(PARTITION, 61_000, 1000),
                                point(PARTITION, 121_000, 1000),
                                point(other, 1000, 1000),
                                point(other, 61_000, 1000))));
        new KafkaConfiguration()
                .rebalanceListener(engine)
                .onPartitionsRevokedBeforeCommit(null, List.of(PARTITION));
        transitions.addAll(
                engine.processBatch(
                        List.of(
                                point(PARTITION, 0, 1000),
                                point(PARTITION, 60_000, 1000),
                                point(PARTITION, 120_000, 1000),
                                point(other, 121_000, 1000))));
        assertThat(transitions).hasSize(3);
        new KafkaConfiguration().rebalanceListener(engine).onPartitionsLost(null, List.of(other));
        transitions.addAll(
                engine.processBatch(
                        List.of(
                                point(other, 0, 1000),
                                point(other, 60_000, 1000),
                                point(other, 120_000, 1000))));
        assertThat(transitions).hasSize(4);
    }

    @Test
    void revokedPendingEntryRequiresNewConsecutivePoints() {
        var engine = engine(GeofenceSettings.Mode.HYSTERESIS);
        transitions.addAll(
                engine.processBatch(
                        List.of(point(PARTITION, 1000, 1000), point(PARTITION, 61_000, 1000))));
        new KafkaConfiguration()
                .rebalanceListener(engine)
                .onPartitionsRevokedBeforeCommit(null, List.of(PARTITION));
        transitions.addAll(engine.processBatch(List.of(point(PARTITION, 121_000, 1000))));
        assertThat(transitions).isEmpty();
    }

    @Test
    void syntheticReplayFixtureProducesExpectedModeComparison() throws Exception {
        var observations = new ArrayList<PartitionPosition>();
        var csv = Path.of("../replayer/src/test/resources/los-angeles-geofence.csv");
        var lines = Files.readAllLines(csv);
        for (var line : lines.subList(1, lines.size())) {
            var columns = line.split(",");
            long ts = LocalDateTime.parse(columns[1]).toInstant(ZoneOffset.UTC).toEpochMilli();
            var position =
                    new Position(
                            1,
                            366000000,
                            366000000,
                            ts,
                            Double.parseDouble(columns[2]),
                            Double.parseDouble(columns[3]),
                            null,
                            null,
                            null,
                            ts);
            observations.add(new PartitionPosition(PARTITION, position));
        }
        transitions.addAll(engine(GeofenceSettings.Mode.HYSTERESIS).processBatch(observations));
        assertThat(transitions)
                .extracting(Transition::type)
                .containsExactly(Transition.Type.ENTER, Transition.Type.EXIT);
        transitions.clear();
        transitions.addAll(engine(GeofenceSettings.Mode.NAIVE).processBatch(observations));
        assertThat(transitions.stream().filter(t -> t.type() == Transition.Type.ENTER)).hasSize(4);
        assertThat(transitions.stream().filter(t -> t.type() == Transition.Type.EXIT)).hasSize(4);
    }
}
