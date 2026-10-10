# ADR 042: Python copilot with a hand-written tool loop

Status: accepted. Supersedes ADRs 021, 023 and 024; amends ADR 022.

## Context

The copilot depends on an external model and is the least stable part of the system. Requirement N4.3 keeps its memory exhaustion, crashes and frequent prompt changes away from position processing, so it stays a separate service. At 100 questions per company per minute about 330 requests wait on the model at once (N6.3).

## Decision

- Rewrite the copilot in Python (FastAPI). Call the API over gRPC from the shared proto; the copilot never connects to a database.
- Call DeepSeek through the official OpenAI SDK and write the tool loop directly (8 tool calls, 30 s deadline), instead of a framework: the flow is one loop over fixed tools, and the company ID, call cap and deadline must be explicit.
- Async IO for concurrent waits; a circuit breaker only around model calls (N6.4); no breaker, retry or stale cache for the internal API.
- Conversation history in Redis with 30-minute expiry, a turn cap and a per-conversation lock, so follow-ups work on any instance (F5.8).
- Remove alert-rule drafts.
- If the API cannot be reached at the start of a question, answer the outage without calling the model.

## Consequences

Two languages share one proto. Model-provider failures fail fast; API failures are reported per question.
