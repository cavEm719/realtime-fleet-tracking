# ADR 013: Topic-owned polling relay and downstream ordering guard

Status: accepted in the implemented experiment. History: `733f4a1`.

## Context

SKIP LOCKED excludes another claimant but lets a later row publish first.

## Decision

API relays fence-changes, processors relay fence-events; guard asset/fence position time downstream instead of leader/sharded relay.

## Consequences

Older delayed events may be suppressed in live state delivery; polling is simpler than CDC but outbox cleanup is absent.

Implementation, tests and limits: [design](../design/consistency.md).
