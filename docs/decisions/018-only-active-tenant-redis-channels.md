# ADR 018: Only active tenant Redis channels

Status: accepted in the implemented experiment. History: `cd6cd9c`.

## Context

A gateway with no local company sessions does not need that company position traffic.

## Decision

Register exact channel on first local session and remove on last under serialized lifecycle changes; early-filter Kafka events.

## Consequences

Kafka still broadcasts all events; larger deployment may need tenant topics or routing.

Implementation, tests and limits: [design](../design/tenancy.md).
