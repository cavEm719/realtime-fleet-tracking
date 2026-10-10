# ADR 037: Requirements-driven simplification

Status: accepted. Basis: [requirements](../requirements.md).

## Context

The design grew from a list of mechanisms to learn rather than from stated scale and availability needs, so it was impossible to tell which mechanisms were redundant.

## Decision

Write measurable requirements (50 companies, 2,500 positions/s peak, 2,500 connections, 2 s p99, two instances per service, 100 copilot questions per company per minute) and keep a design only if it serves one. Remove designs that serve none and duplicates that serve the same one.

## Consequences

ADRs 038–044 record the removals and replacements. Several first-pass "redundant" verdicts were wrong after checking code (event-ID dedupe on resume, gateway virtual threads, resume protocol); requirements §7.2 records them.
