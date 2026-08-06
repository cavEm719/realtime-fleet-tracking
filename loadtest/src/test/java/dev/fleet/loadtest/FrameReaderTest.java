package dev.fleet.loadtest;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.LongAdder;

class FrameReaderTest {
    @Test
    void streamingParserCollectsOnlyDirectPositionTimestampsAndSeparatesGeneratorDelay() {
        var pipeline = new LatencyHistogram();
        var endToEnd = new LatencyHistogram();
        var missing = new LongAdder();
        String type =
                FrameReader.read(
                        """
                        {"type":"positions","positions":[
                          {"ingestedAt":1000,"emittedAt":500,"lat":33.7,"unknown":{"ingestedAt":1}},
                          {"emittedAt":700,"ingestedAt":1200}]}
                        """,
                        1500,
                        pipeline,
                        endToEnd,
                        missing);
        assertThat(type).isEqualTo("positions");
        assertThat(pipeline.count()).isEqualTo(2);
        assertThat(pipeline.percentile(.5)).isEqualTo(300);
        assertThat(pipeline.percentile(.99)).isEqualTo(500);
        assertThat(endToEnd.percentile(.99)).isEqualTo(1000);
        assertThat(missing.sum()).isZero();
    }

    @Test
    void eventTimestampsAndMissingOrNegativeSamplesAreReportedExplicitly() {
        var pipeline = new LatencyHistogram();
        var endToEnd = new LatencyHistogram();
        var missing = new LongAdder();
        FrameReader.read(
                "{\"type\":\"fence-event\",\"event\":{\"ingestedAt\":2000,\"emittedAt\":1000}}",
                1500,
                pipeline,
                endToEnd,
                missing);
        FrameReader.read(
                "{\"type\":\"positions\",\"positions\":[{\"emittedAt\":1000}]}",
                1500,
                pipeline,
                endToEnd,
                missing);
        assertThat(pipeline.count()).isZero();
        assertThat(pipeline.negative()).isEqualTo(1);
        assertThat(missing.sum()).isEqualTo(1);
        assertThat(endToEnd.count()).isEqualTo(2);
    }
}
