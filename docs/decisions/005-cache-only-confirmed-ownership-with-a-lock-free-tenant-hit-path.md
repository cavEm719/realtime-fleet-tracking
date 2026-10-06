# ADR 005: Cache only confirmed ownership with a lock-free tenant hit path

Status: accepted in the implemented experiment. History: `a219930 / 4f848ba`.

## Context

Frequent SQL ownership checks and compute on every tenant cache hit add avoidable work.

## Decision

Cache registered ownership after commit; short-lived config entries read via get before compute on a miss/expiry.

## Consequences

Registration assumptions and policy TTL remain explicit; no measured bin-lock speedup is claimed.

Implementation, tests and limits: [design](../design/tenancy.md).
