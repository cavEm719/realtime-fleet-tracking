# ADR 033: ISO UTC presentation instead of model conversion

Status: accepted in the implemented experiment. History: `c6abbc3`; amended by ADR 044.

## Context

Models converted epoch numbers incorrectly and mixed event times.

## Decision

Every epoch-millisecond tool value also carries ISO UTC and the prompt tells the model to quote it.

## Consequences

Rounding/event association mistakes can still occur; a limited zero-error detector is not full accuracy.

Implementation, tests and limits: [design](../design/copilot.md).
