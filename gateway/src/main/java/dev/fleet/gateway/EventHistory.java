package dev.fleet.gateway;

import dev.fleet.common.GeofenceEvent;

import java.util.List;

@FunctionalInterface
public interface EventHistory {
    record Cursor(long createdAtMicros, long id) {
        public Cursor {
            if (createdAtMicros <= 0 || id <= 0) {
                throw new IllegalArgumentException("Invalid resume cursor");
            }
        }
    }

    List<GeofenceEvent> since(long tenant, List<Long> assets, boolean all, Cursor after);
}
