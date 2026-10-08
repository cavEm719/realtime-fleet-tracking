# ADR 015: Bounded mailboxes rather than blocking publisher sends

Status: accepted in the implemented experiment. History: `9755193 / 3f418da`.

## Context

A stopped reader must not hold the publication path for other sessions.

## Decision

Per-session serial sender, bounded bytes, send-time checks and isolated close work; raw JSON WebSocket instead of STOMP.

## Consequences

Overflow terminates a session and requires reconnect/resync policy.

Implementation, tests and limits: [design](../design/websocket.md).
