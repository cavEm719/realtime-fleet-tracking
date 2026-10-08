# ADR 020: Creation-time visibility overlap and confirmed-seen IDs

Status: accepted in the implemented experiment. History: `d6e8910`.

## Context

Event-time cursors skip late reports; IDs/timestamps can also commit out of allocation order.

## Decision

Use precise created_at/id and bounded overlap, seed only explicit client-confirmed tenant-owned IDs.

## Consequences

Not a true commit-log cursor; duplicates are required and long delays can exceed overlap. Seeding all old overlap rows was rejected before implementation.

Implementation, tests and limits: [design](../design/resume.md).
