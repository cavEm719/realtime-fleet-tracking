# ADR 034: Validated ID-only semantic queries

Status: accepted in the implemented experiment. History: `2114095`.

## Context

Natural-language names can be ambiguous and arbitrary SQL cannot be bounded simply.

## Decision

Resolve names first; validate ID-only metrics/filter/time/group spec and compile parameterized whitelisted SQL.

## Consequences

Ambiguity needs clarification; supported semantics intentionally omit arbitrary joins/expressions.

Implementation, tests and limits: [design](../design/query-transparency.md).
