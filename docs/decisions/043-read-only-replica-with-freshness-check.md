# ADR 043: Copilot reads on a read-only replica with a freshness check

Status: accepted.

## Context

Copilot statistics queries must not take connections or I/O from position processing, and the copilot must not be able to write (F5.9).

## Decision

The API serves copilot RPCs from a separate read-only pool, pointed at an asynchronous GTID replica with a SELECT-only account. The API writes a heartbeat row on the primary every second; each copilot RPC reads it on the replica, returns it as the data-as-of time and fails with `UNAVAILABLE` ("Replicated data is delayed") when it is more than 60 seconds old. Gateway resume keeps reading the primary. The gRPC server uses a fixed thread pool.

## Consequences

Answers can be up to a minute behind the live page; every answer says how current its port data is. Replica failure makes the copilot report that data cannot be queried rather than reading the primary.
