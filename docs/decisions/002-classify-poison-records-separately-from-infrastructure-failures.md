# ADR 002: Classify poison records separately from infrastructure failures

Status: accepted in the implemented experiment. History: `0601b2a`.

## Context

A malformed middle Kafka record would repeatedly fail the partition under universal infinite retry.

## Decision

Apply the valid prefix, throw an indexed batch failure, publish the bad record to positions.DLT, and retry temporary infrastructure failures with capped backoff.

## Consequences

DLT publication must succeed before recovering offsets; valid suffix records remain processable.

Implementation, tests and limits: [design](../design/ingestion.md).
