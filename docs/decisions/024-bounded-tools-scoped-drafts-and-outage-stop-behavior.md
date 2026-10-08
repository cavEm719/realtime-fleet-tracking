# ADR 024: Bounded tools, scoped drafts and outage-stop behavior

Status: accepted in the implemented experiment. History: `7ce5a65 / c743f38 / c6abbc3`; superseded by ADR 042.

## Context

A model loop can overspend tools, fabricate recovery or imply an active notification service.

## Decision

Code-enforced cap and result limits, server tenant context, DRAFT-only creation, explicit stored-only semantics; stop dependent tools after outage.

## Consequences

Normal cap reply differs from quota 429; ACTIVE confirmation still does not evaluate or notify.

Implementation, tests and limits: [design](../design/copilot.md).
