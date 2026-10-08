# ADR 016: Immutable subscription sets on the read path

Status: accepted in the implemented experiment. History: `608d17d`; amended by ADR 040.

## Context

Every position previously took a global lock and copied company subscribers.

## Decision

Write-side locks build immutable asset/tenant sets; reads iterate published sets without a copy.

## Consequences

Subscription storms pay copy-on-write costs; steady traffic avoids locks.

Implementation, tests and limits: [design](../design/websocket.md).
