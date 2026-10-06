# ADR 008: Copy-on-write grid snapshots

Status: accepted in the implemented experiment. History: `ded82c6`; superseded by ADR 038.

## Context

Frequent lookup does not need a read lock when fence updates are rare.

## Decision

Publish immutable snapshots through a volatile reference; writers rebuild under one lock.

## Consequences

Writes allocate/rebuild; readers see a coherent old or new snapshot.

Implementation, tests and limits: [design](../design/geofence.md).
