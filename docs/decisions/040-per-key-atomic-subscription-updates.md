# ADR 040: Per-key atomic subscription updates

Status: accepted. Amends ADR 016.

## Context

Immutable subscriber sets already keep delivery lock-free. The 64 shard locks only spread writer contention, which `ConcurrentHashMap.compute` already serializes per asset and per company.

## Decision

Replace the shards and explicit locks with `compute`/`computeIfPresent` that publish a new immutable set.

## Consequences

Same guarantees (no lost concurrent subscriptions, lock-free reads) with less code.
