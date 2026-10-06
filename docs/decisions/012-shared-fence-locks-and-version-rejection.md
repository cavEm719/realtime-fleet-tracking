# ADR 012: Shared fence locks and version rejection

Status: accepted in the implemented experiment. History: `53b8926`; superseded by ADR 039.

## Context

A geometry edit/delete can race a confirmation based on an old snapshot.

## Decision

FOR SHARE active fence row, compare the evaluated version inside the transition transaction and invalidate stale cache use.

## Consequences

Version rejection omits stale confirmations; no backfilled historical crossings for edits.

Implementation, tests and limits: [design](../design/consistency.md).
