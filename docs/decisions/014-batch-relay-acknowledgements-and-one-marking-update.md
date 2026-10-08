# ADR 014: Batch relay acknowledgements and one marking update

Status: accepted in the implemented experiment. History: `14d61d4`.

## Context

Serial network waits hold row locks longer and limit relay throughput.

## Decision

Send all locked rows asynchronously under one overall timeout, then one UPDATE id IN marks acknowledged publication.

## Consequences

Failure rolls back all marks; earlier successful sends repeat and consumers dedupe IDs.

Implementation, tests and limits: [design](../design/consistency.md).
