# ADR 023: Deadlines, read retries, breaker and last-known policy

Status: accepted in the implemented experiment. History: `165d05a / 964d451`; superseded by ADR 042.

## Context

API hangs/outages should not turn questions into indefinite waits or needless denial when policy is cached.

## Decision

One remaining-call budget, one UNAVAILABLE read retry, never retry writes; breaker returns unavailable, cache serves known policy on refresh error.

## Consequences

Stale policy can remain stale without a maximum age; uncertain writes are explicit.

Implementation, tests and limits: [design](../design/copilot.md).
