# ADR 035: Execution receipts and code-rendered basis/results

Status: accepted in the implemented experiment. History: `6af5dc3 / b82722e`; amended by ADR 044.

## Context

Model prose can misdescribe executed scope or invent counts.

## Decision

One validated spec supplies SQL and receipt, database names, rendered conditions, assumptions and result tables.

## Consequences

Summary prose can still err; intent matching is not guaranteed; feature has no live model score yet.

Implementation, tests and limits: [design](../design/query-transparency.md).
