# ADR 006: Geofence evaluation is independent of Redis acceptance

Status: accepted in the implemented experiment. History: `e2296d6`.

## Context

Redis may succeed before MySQL, then replay would be filtered if Redis flags controlled the engine.

## Decision

Pass the full decoded batch and maintain partition-owned timestamp/candidate state.

## Consequences

Transient state is intentionally lost on rebalance/crash; MySQL confirmed state must rebuild it.

Implementation, tests and limits: [design](../design/geofence.md).
