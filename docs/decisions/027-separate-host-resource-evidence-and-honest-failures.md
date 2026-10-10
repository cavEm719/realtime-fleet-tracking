# ADR 027: Separate-host resource evidence and honest failures

Status: accepted in the implemented experiment. History: `ffab8ae / a536353 / 143687b`.

## Context

Laptop co-location can obscure why adding processors fails to increase throughput.

## Decision

Use separate AWS hosts, clock snapshots, aligned CPU/container/network/Redis/lag samples and three trials; keep missing/reset values unknown.

## Consequences

Observed API limit is not a processor maximum; short trials and clocks limit extrapolation.

Implementation, tests and limits: [design](../design/performance.md).
