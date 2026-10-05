# Recovering missed events after reconnect

## What: the problem in plain words, with a concrete example

A dashboard disconnects while port events continue to commit. When it reconnects, it supplies its last database cursor and IDs it actually saw. The gateway fetches missed durable events, holds arriving live events during catch-up and then resumes delivery. A late AIS report can have an earlier observation time than the event displayed before disconnection; using observation time as the resume cursor would miss it.

## Why: requirements and constraints that drove the design

History must be tenant/subscription scoped, paginated, bounded and compatible with duplicate delivery. Creation time and row ID order database rows, but concurrent transactions may become visible in the opposite order. A limited overlap recovers recently late commits; only confirmed client-seen IDs can safely seed dedupe.

## How: the design, with pointers to key code (file:line), SQL, Lua, proto

The gateway protocol accepts resumeAfter with createdAtMicros and id, plus up to 200 validated SHA-256 seenEventIds. The internal API additionally accepts afterEventId and resolves that ID to a cursor; the current gateway command uses the explicit cursor, not that alternate API field. The API honors only IDs belonging to the session tenant. geofence_event.created_at is TIMESTAMP(6); the resume index starts with tenant, creation time and ID. Queries include a default five-second look-back before the anchor and paginate by (created_at,id), independent of position_ts.

A catch-up session buffers live events, retrieves at most the configured gap under an overall RPC deadline, orders replay rows, then drains buffered events through the same ID and asset/fence timestamp guards. Only confirmed IDs seed the new connection's otherwise empty LRU. Every other overlap row is eligible for replay. Too large a gap or failed catch-up emits resync_required rather than a partial success. Positions are latest-value only, not historical replay.

[GatewaySocketHandler.java:16](../../gateway/src/main/java/dev/fleet/gateway/GatewaySocketHandler.java#L16); [GatewayHub.java:133](../../gateway/src/main/java/dev/fleet/gateway/GatewayHub.java#L133); [GrpcEventHistory.java:38](../../gateway/src/main/java/dev/fleet/gateway/GrpcEventHistory.java#L38); [FleetQueries.java:71](../../api/src/main/java/dev/fleet/api/internal/FleetQueries.java#L71); [V7__event_visibility_resume.sql:1](../../api/src/main/resources/db/migration/V7__event_visibility_resume.sql#L1).

**Reconnect command fields:**

| Field | Source and use | Absence |
|---|---|---|
| type | Client command, subscribe starts/replaces the selected scope | Required. |
| all | Client selects company-wide scope; server binds its company | False/absent requires a nonempty assetIds list. |
| assetIds | Client-selected vessel IDs, validated for the bound tenant | Omitted for all=true; both scopes together are rejected. |
| resumeAfter.createdAtMicros | Creation instant from the last received event, microseconds since UTC epoch | No resumeAfter means ordinary live subscription. |
| resumeAfter.id | Database row ID from that same event; breaks equal-time ties | Required with a cursor. |
| seenEventIds | Actual received IDs retained by the client, checked against tenant history before seeding | Optional; an empty list means overlap duplicates can be replayed. |

## Alternatives considered and trade-offs

An event-time cursor is invalid for newly committed old reports. A bare auto-increment ID is also not commit order. A true database commit log/CDC offset would avoid finite overlap ambiguity but introduces a different history service. The current creation-time cursor plus bounded overlap is deliberately at-least-once. Seeding all pre-cursor overlap rows would incorrectly mark never-delivered late commits as seen; it is forbidden.

## Failure modes and how each is handled

Live arrivals during catch-up are buffered and deduped against history. Reconnect loses server-local dedupe, so clients must dedupe event_id themselves. Earlier report A committed after B is replayed, provided the existing per-pair ordering guard permits it; a new session may receive A before later replay rows, then advance normally. The cursor is an insertion/visibility proxy, not mathematically exact commit order. Transactions delayed beyond the overlap or commits appearing behind an already-read page can escape this bounded strategy; no unbounded no-loss guarantee is claimed.

## Problems encountered and how they were solved

ac1c316 adds missed-event history. d6e8910 replaces event-time ordering with precise creation time/ID and overlap, and honors only explicit seen IDs. The proposal to seed every pre-cursor overlap row was rejected before that commit; no committed implementation doing so was found. The tests reproduce late-event visibility rather than claiming a live outage. Dynamic tenant subscriptions were added separately by cd6cd9c.

## Evidence: tests by name, measured numbers with conditions

GatewayResumeTest.reconnectReplaysMissedEventsThenSwitchesToLiveWithoutReplayingCursor, liveDuringCatchupIsBufferedSortedAndDedupedAgainstHistory, earlierReportCommittedLaterIsDeliveredOnceAndOnlyConfirmedTenantIdsAreSeeded, seenIdsAreBoundedAndMalformedIdsAreRejectedBeforeSubscription and oversizedGapRequiresResyncWithoutPartialHistoryOrLaterLiveEvents. IngestIT.smallerIdTransactionCommittingAfterLargerIdIsVisibleWithinOverlap exercises real MySQL visibility. [Visibility verification](../verification/phase62-visibility.md) records the scenarios.

## Known limits and what production would need

Defaults are 1,000 catch-up events, five-second overlap (configurable up to 60 seconds), 50 rows per gRPC page and 1,024 server ID entries. These are configuration limits, not measured throughput. No durable client acknowledgement log exists. A production history service needs retention, authenticated scope and a true commit-order source if arbitrarily delayed transactions must never be missed.

## Likely interview questions with short, precise answers

**Q: Why not position_ts as cursor?**

A: A late report can commit after the cursor with an earlier source time.

**Q: Does ID alone order commits?**

A: No; one transaction can allocate an ID and commit after another.

**Q: What does overlap do?**

A: Re-queries recent pre-cursor rows to catch delayed visibility.

**Q: Why not seed every overlap row?**

A: Some were committed late and never delivered.

**Q: What seeds dedupe?**

A: Only client-confirmed IDs validated as tenant-owned.

**Q: How is live traffic handled during replay?**

A: It is buffered, then drained through the ordinary guards.

**Q: Is delivery exactly once?**

A: No; clients must dedupe IDs across connections.

**Q: Why retain the timestamp guard?**

A: It prevents old transitions from reversing current displayed state.

**Q: What if the gap is excessive?**

A: Send resync_required instead of claiming full catch-up.

**Q: Is five seconds a complete guarantee?**

A: No; longer transaction visibility delay can exceed the recovery window.
