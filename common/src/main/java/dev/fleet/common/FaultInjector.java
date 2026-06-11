package dev.fleet.common;

/** Production uses the inert bean. Only tests replace it; there is no runtime enable switch. */
@FunctionalInterface
public interface FaultInjector {
    enum Point {
        BEFORE_TX,
        AFTER_TX_BEFORE_OFFSET_COMMIT,
        AFTER_PUBLISH_BEFORE_MARK
    }

    void hit(Point point);

    static FaultInjector disabled() {
        return point -> {};
    }
}
