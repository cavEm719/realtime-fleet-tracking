# ADR 009: Load authoritative confirmation before replay evaluation

Status: accepted in the implemented experiment. History: `53b8926`.

## Context

Candidate loss can move confirmation to a later timestamp and bypass event-ID uniqueness.

## Decision

Batch-load all unseen assets from MySQL before evaluating them.

## Consequences

Database reads are required on first use; confirmed state, not the ID hash, prevents replay re-entry.

Implementation, tests and limits: [design](../design/consistency.md).
