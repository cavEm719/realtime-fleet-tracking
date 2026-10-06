# ADR 001: One Redis script for dedupe and position effects

Status: accepted in the implemented experiment. History: `39ba22a`.

## Context

A crash between recording a timestamp and publishing its position could make replay invisible. The gap was caught before the first committed core, not reproduced in production.

## Decision

Use one Lua script, compare the full batch before writing, trust validated coordinates and owned key types.

## Consequences

Redis runtime errors do not roll back; one node and trusted key/permission invariants remain necessary.

Implementation, tests and limits: [design](../design/ingestion.md).
