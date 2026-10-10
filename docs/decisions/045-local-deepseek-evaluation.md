# ADR 045: Local evaluation against the DeepSeek API

Status: accepted. Amends ADRs 030–032.

## Context

The copilot was rewritten and the model changed, so the round-3 scores no longer describe the code. AWS Bedrock is no longer used.

## Decision

Run the tasks on the local compose stack against DeepSeek's API with the frozen checker (`run_local.py`). Task set v3 drops alert-draft tasks, turns circuit-open tasks into plain outages and adds cross-instance follow-ups and replica-delay tasks. Every checker failure is reviewed; cost is capped by a token-based guard.

## Consequences

v3 scores are not comparable one-to-one with rounds 1–3 (different tasks, model and service). Earlier results remain as history.
