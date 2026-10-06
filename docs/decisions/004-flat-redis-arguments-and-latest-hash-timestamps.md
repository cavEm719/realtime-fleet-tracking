# ADR 004: Flat Redis arguments and latest-hash timestamps

Status: accepted in the implemented experiment. History: `59e5184 / 8c1cc19`.

## Context

Double-encoded JSON and a separate last-ts key add work to shared single-threaded Redis.

## Decision

Validate in Java, pass flat timestamp/longitude/latitude/payload, use latest.position_ts and remove defensive type/ACL checks.

## Consequences

Trust key ownership and producer validation; arbitrary key corruption is outside that hot-path guarantee.

Implementation, tests and limits: [design](../design/ingestion.md).
