# ADR 041: Remove distributed tracing

Status: accepted. Supersedes ADR 025.

## Context

No requirement asks for request tracing; benchmarks already ran with tracing disabled.

## Decision

Remove OpenTelemetry, trace propagation through Kafka and the outbox `traceparent` column (migration V8). Keep logs, health checks and Prometheus metrics.

## Consequences

Cross-service latency questions rely on metrics and logs. Adding an observability requirement would justify bringing tracing back.
