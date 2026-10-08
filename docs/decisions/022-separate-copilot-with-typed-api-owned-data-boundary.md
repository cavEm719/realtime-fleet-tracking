# ADR 022: Separate copilot with typed API-owned data boundary

Status: accepted in the implemented experiment. History: `165d05a / e4fdab3`; amended by ADR 042.

## Context

Slow model calls share ingest resources and direct data reads share schemas.

## Decision

Separate JVM, typed proto/gRPC; only shared infrastructure quota Redis access is permitted.

## Consequences

A process bulkhead costs deployment complexity; internal service authentication still absent.

Implementation, tests and limits: [design](../design/copilot.md).
