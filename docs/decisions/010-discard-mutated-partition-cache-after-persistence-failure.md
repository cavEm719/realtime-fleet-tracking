# ADR 010: Discard mutated partition cache after persistence failure

Status: accepted in the implemented experiment. History: `53b8926`.

## Context

The engine can contain confirmations a failed transaction never saved.

## Decision

Persist transitions in one transaction; any failure drops all batch partitions, never repairs their memory.

## Consequences

Retry pays reload/confirmation work but derives state from committed rows.

Implementation, tests and limits: [design](../design/consistency.md).
