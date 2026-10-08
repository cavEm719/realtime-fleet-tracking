# ADR 017: Serialize company frames once

Status: accepted in the implemented experiment. History: `13a5f2c`.

## Context

Identical company recipients multiply pending-map/JSON work and heap pressure.

## Decision

One pending map/frame per tenant; share an immutable payload across independent bounded mailboxes.

## Consequences

Network and enqueue/send work still scale with recipients; per-asset path remains separate.

Implementation, tests and limits: [design](../design/websocket.md).
