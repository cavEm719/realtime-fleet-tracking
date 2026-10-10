# ADR 026: Server-receipt latency and bounded generator retention

Status: accepted in the implemented experiment. History: `59f9508`.

## Context

Generator batch waits and retries dominated old latency; retained messages exhausted generator memory.

## Decision

Add ingestedAt before body decoding, keep emittedAt secondary, retain only histograms/timestamps for clients.

## Consequences

Coalesced observations and paused clients are not latency samples; quota-free latency scenarios differ from max-rate runs.

Implementation, tests and limits: [design](../design/performance.md).
