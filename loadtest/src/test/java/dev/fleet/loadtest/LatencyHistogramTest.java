package dev.fleet.loadtest;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LatencyHistogramTest {
    @Test
    void reportsNearestRankAndExplicitOverflow() {
        var histogram = new LatencyHistogram();
        assertThat(histogram.percentile(.99)).isNull();
        for (int i = 1; i <= 100; i++) histogram.record(i);
        assertThat(histogram.percentile(.5)).isEqualTo(50);
        assertThat(histogram.percentile(.95)).isEqualTo(95);
        assertThat(histogram.percentile(.99)).isEqualTo(99);
        histogram.record(400000);
        assertThat(histogram.overflow()).isEqualTo(1);
    }
}
