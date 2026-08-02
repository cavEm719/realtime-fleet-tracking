package dev.fleet.replayer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** One shared batch schedule across all lanes; zero means unrestricted max-rate replay. */
final class RatePacer {
    private final int positionsPerSecond;
    private final AtomicLong next = new AtomicLong();

    RatePacer(int positionsPerSecond) {
        if (positionsPerSecond < 0) {
            throw new IllegalArgumentException("Offered rate cannot be negative");
        }
        this.positionsPerSecond = positionsPerSecond;
    }

    long reserve(int positions, long now) {
        if (positionsPerSecond == 0) {
            return now;
        }
        long duration = Math.max(1, positions * 1_000_000_000L / positionsPerSecond);
        long previous = next.getAndUpdate(slot -> Math.max(slot, now) + duration);
        return Math.max(previous, now);
    }

    void await(int positions) throws InterruptedException {
        long due = reserve(positions, System.nanoTime());
        long remaining;
        while ((remaining = due - System.nanoTime()) > 0) {
            Thread.sleep(Duration.ofNanos(Math.min(remaining, 50_000_000L)));
        }
    }
}
