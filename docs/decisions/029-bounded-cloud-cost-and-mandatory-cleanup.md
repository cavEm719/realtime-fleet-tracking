# ADR 029: Bounded cloud cost and mandatory cleanup

Status: accepted in the implemented experiment. History: `6e7f977 / bab6bba`.

## Context

Experimental hosts incur cost even during failure or gate waits.

## Decision

Keep launch-time ledger/reserves/deadlines, copy evidence, destroy Terraform fleet and audit direct active resources.

## Consequences

Estimates are not invoices; stale tagging history alone cannot establish live resources.

Implementation, tests and limits: [design](../design/aws.md).
