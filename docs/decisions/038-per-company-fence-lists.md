# ADR 038: Per-company fence lists instead of a grid

Status: accepted. Supersedes the grid part of ADR 007 and ADR 008.

## Context

A company watches at most about 20 ports. At the 2,500 positions/s peak, checking every port of the position's company is about 50,000 distance calculations per second.

## Decision

Keep one immutable list of active fences per company and check them all. Writers still replace the whole snapshot (copy on write), so the per-position path takes no lock. Hysteresis and confirmation are unchanged.

## Consequences

Less code and no cell-size setting. A company monitoring thousands of areas would need a spatial index again.
