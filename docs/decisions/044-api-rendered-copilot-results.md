# ADR 044: API-rendered copilot results

Status: accepted. Amends ADRs 033 and 035.

## Context

Conditions and tables shown to the operator must come from the executed query. After the rewrite the copilot is in another language; rendering in two places would let them drift.

## Decision

Each copilot RPC returns a `Presentation`: the JSON the model reads (ISO-8601 UTC beside every epoch value), the query conditions, result tables and the data-as-of time. The copilot adds only assumptions that depend on the operator's wording and assembles the answer.

## Consequences

The API owns query, receipt and rendering together; the copilot cannot alter the conditions it displays.
