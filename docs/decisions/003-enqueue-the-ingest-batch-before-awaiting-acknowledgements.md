# ADR 003: Enqueue the ingest batch before awaiting acknowledgements

Status: accepted in the implemented experiment. History: `5c7b2ac / d1b14df`.

## Context

Waiting per record prevents useful producer concurrency.

## Decision

Idempotent producer, at most five in flight, enqueue all then wait within one deadline including enqueue time; linger and compression amortize send cost.

## Consequences

HTTP uncertain acceptance still retries the complete batch and may duplicate records.

Implementation, tests and limits: [design](../design/ingestion.md).
