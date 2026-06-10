package dev.fleet.common;

public final class Topics {
    public static final String POSITIONS = "positions";
    public static final String POSITIONS_DLT = POSITIONS + ".DLT";
    public static final String FENCE_EVENTS = "fence-events";
    public static final String FENCE_CHANGES = "fence-changes";
    public static final int POSITION_PARTITIONS = 12;

    private Topics() {}
}
