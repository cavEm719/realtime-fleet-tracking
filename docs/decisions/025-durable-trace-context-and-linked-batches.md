# ADR 025: Durable trace context and linked batches

Status: accepted in the implemented experiment. History: `45b1dbb / 045943a`; superseded by ADR 041.

## Context

Thread context is lost across delayed publication and one batch has several origins.

## Decision

Store traceparent in outbox, restore on relay/sender, link record contexts in a no-parent batch span.

## Consequences

Tracing is optional/sampled; Redis position flow is not claimed as the same complete event trace.

The tracing design document was removed with tracing; see ADR 041.
