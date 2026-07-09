package dev.fleet.processor.geofence;

import java.util.List;

/** Persists one batch; false requests cache invalidation when a stale transition was skipped. */
@FunctionalInterface
public interface TransitionSink {
    boolean persist(List<Transition> transitions);
}
