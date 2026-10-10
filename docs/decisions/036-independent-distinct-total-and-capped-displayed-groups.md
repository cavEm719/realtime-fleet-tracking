# ADR 036: Independent distinct total and capped displayed groups

Status: accepted in the implemented experiment. History: `2114095`.

## Context

One vessel can appear in several port/day groups, making their sum exceed the total.

## Decision

Compute overall distinct count separately in the same read snapshot; cap/display group truncation separately.

## Consequences

Full total is meaningful even with a truncated table; table rows cannot be used to reconstruct it.

Implementation, tests and limits: [design](../design/query-transparency.md).
