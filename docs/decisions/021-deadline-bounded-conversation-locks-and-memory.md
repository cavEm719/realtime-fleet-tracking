# ADR 021: Deadline-bounded conversation locks and memory

Status: accepted in the implemented experiment. History: `97786c4 / b7bec69`; superseded by ADR 042.

## Context

Blocking model I/O inside synchronized can pin Java 21 carriers; unbounded history grows heap.

## Decision

ReentrantLock.tryLock with remaining time, leased bounded LRU/TTL keyed by company and conversation.

## Consequences

Per-instance memory disappears on restart; short gateway monitors contain no blocking I/O.

Implementation, tests and limits: [design](../design/copilot.md).
