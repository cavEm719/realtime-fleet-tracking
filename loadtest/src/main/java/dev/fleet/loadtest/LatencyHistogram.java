package dev.fleet.loadtest;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Millisecond-resolution bounded histogram, including an explicit overflow bin. */
final class LatencyHistogram {
    private static final int MAX = 300000;
    private final AtomicLongArray bins = new AtomicLongArray(MAX + 1);
    private final LongAdder count = new LongAdder();
    private final LongAdder negative = new LongAdder();

    void record(long ms) {
        if (ms < 0) {
            negative.increment();
            return;
        }
        bins.incrementAndGet((int) Math.max(0, Math.min(MAX, ms)));
        count.increment();
    }

    long count() {
        return count.sum();
    }

    long negative() {
        return negative.sum();
    }

    long overflow() {
        return bins.get(MAX);
    }

    Long percentile(double p) {
        long total = count();
        if (total == 0) return null;
        long rank = (long) Math.ceil(total * p);
        long cumulative = 0;
        for (int i = 0; i <= MAX; i++) {
            cumulative += bins.get(i);
            if (cumulative >= rank) return (long) i;
        }
        return null;
    }

    void save(Path file) throws IOException {
        try (var out = Files.newBufferedWriter(file)) {
            out.write("latency_ms,count\n");
            for (int i = 0; i <= MAX; i++) {
                long value = bins.get(i);
                if (value > 0) out.write(i + "," + value + "\n");
            }
        }
    }
}
