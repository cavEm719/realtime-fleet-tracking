# ADR 011: Conditional state, event and pending publication in one transaction

Status: accepted in the implemented experiment. History: `53b8926 / ae4bcc1`.

## Context

Saving an arrival separately from its notification can strand one without the other.

## Decision

Conditional state update, INSERT IGNORE event, and outbox only for a new event in one MySQL transaction; no 2PC/Seata.

## Consequences

Atomicity is local to MySQL; Kafka sends remain at-least-once.

Implementation, tests and limits: [design](../design/consistency.md).
