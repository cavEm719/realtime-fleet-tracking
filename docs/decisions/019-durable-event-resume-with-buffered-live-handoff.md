# ADR 019: Durable event resume with buffered live handoff

Status: accepted in the implemented experiment. History: `ac1c316`.

## Context

A disconnected dashboard loses transient delivery even though events are saved.

## Decision

Fetch scoped bounded API history before draining buffered live events through normal guards.

## Consequences

Positions are not replayed; excessive gaps produce resync_required.

Implementation, tests and limits: [design](../design/resume.md).
