package dev.fleet.processor.geofence;

import dev.fleet.common.GeofenceEvent;
import dev.fleet.common.Json;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

public final class TransactionalTransitionWriter implements TransitionSink {
    private final StateMapper mapper;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;

    public TransactionalTransitionWriter(
            StateMapper mapper,
            TransactionTemplate transactions,
            MeterRegistry metrics) {
        this.mapper = mapper;
        this.transactions = transactions;
        this.metrics = metrics;
        for (var mode : GeofenceSettings.Mode.values()) {
            for (var type : Transition.Type.values()) {
                counter(mode, type);
            }
        }
    }

    private record BatchWrite(List<Transition> inserted, boolean cacheReusable) {}

    @Override
    public boolean persist(List<Transition> transitions) {
        if (transitions.isEmpty()) {
            return true;
        }
        var result =
                Objects.requireNonNull(transactions.execute(status -> writeBatch(transitions)));
        for (var transition : result.inserted()) {
            counter(transition.mode(), transition.type()).increment();
        }
        return result.cacheReusable();
    }

    private BatchWrite writeBatch(List<Transition> transitions) {
        var inserted = new ArrayList<Transition>();
        boolean cacheReusable = true;
        for (var transition : transitions) {
            int changed = mapper.updateState(transition);
            if (changed == 0) {
                changed = mapper.insertStateIfMissing(transition);
            }
            if (changed == 0) {
                cacheReusable = false;
            }
            if (changed > 0 && mapper.insertEvent(transition) == 1) {
                var commit = Objects.requireNonNull(mapper.eventCommit(transition.eventId()));
                mapper.insertOutbox(transition, Json.write(event(transition, commit)));
                inserted.add(transition);
            }
        }
        return new BatchWrite(inserted, cacheReusable);
    }

    private GeofenceEvent event(Transition transition, StateMapper.EventCommit commit) {
        return new GeofenceEvent(
                transition.eventId(),
                transition.tenantId(),
                transition.assetId(),
                transition.fenceId(),
                transition.fenceVersion(),
                transition.positionTs(),
                GeofenceEvent.Type.valueOf(transition.type().name()),
                transition.ingestedAt(),
                transition.emittedAt(),
                commit.createdAtMicros(),
                commit.id());
    }

    private io.micrometer.core.instrument.Counter counter(
            GeofenceSettings.Mode mode, Transition.Type type) {
        return metrics.counter(
                "geofence.transitions",
                "mode",
                mode.name().toLowerCase(Locale.ROOT),
                "type",
                type.name());
    }
}
