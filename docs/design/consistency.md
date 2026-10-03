# Durable port state and event publication

## What: the problem in plain words, with a concrete example

A port arrival is confirmed, MySQL commits it, and the processor stops before Kafka records its consumed offset. The same reports are replayed. Fleet operators should see one durable arrival, even if confirmation now happens at a later source timestamp. A second failure can occur after sending that event but before recording that it was sent; recovery must preserve the event and tolerate a duplicate delivery.

## Why: requirements and constraints that drove the design

Candidate counters are transient. A deterministic hash of vessel, fence, type and timestamp cannot identify a confirmation at a different timestamp as the same logical arrival. MySQL confirmed state must therefore be authoritative; Redis and partition maps cannot be the only record. MySQL and Kafka cannot be committed by one ordinary local transaction.

## How: the design, with pointers to key code (file:line), SQL, Lua, proto

Before evaluating unseen assets in a batch, one query loads their confirmed rows. The engine processes the full batch and collects transitions. A single MySQL transaction conditionally updates confirmed state only for a newer timestamp and an actual state change, inserts the deterministic event with INSERT IGNORE, and inserts an outbox row only for a newly inserted event. An outbox is a database list of messages waiting to be sent; recording it in the same transaction prevents a durable event without eventual publication.

The relay claims only its owned topic using FOR UPDATE SKIP LOCKED, sends the whole batch asynchronously, waits under one overall deadline, then marks all rows in one UPDATE. API owns fence-changes; processor owns fence-events. Offsets follow successful processing; any persistence exception drops all affected partition state before retry.

Fence edits, version increment and fence-change outbox insertion share an API transaction. Broadcast listeners reload one fence; a periodic database revision comparison repairs missed notifications. Soft deletion removes inside state without EXIT.

[StateMapper.xml:12](../../processor/src/main/resources/mapper/StateMapper.xml#L12); [TransactionalTransitionWriter.java:58](../../processor/src/main/java/dev/fleet/processor/geofence/TransactionalTransitionWriter.java#L58); [FenceCache.java:1](../../processor/src/main/java/dev/fleet/processor/geofence/FenceCache.java#L1); [OutboxRelay.java:70](../../common/src/main/java/dev/fleet/common/outbox/OutboxRelay.java#L70).

**Values that control recovery:**

| Stored value | Produced and read at which step | Why it matters / optionality |
|---|---|---|
| asset_fence_state.inside | Transaction writes confirmed inside/outside; unseen-asset load rebuilds the engine | Prevents a later-timestamp replay generating another logical arrival. A missing row starts outside. |
| last_transition_position_ts | Confirming source timestamp written with the state | SQL only accepts newer actual state changes; required in a stored row. |
| fence_version | Index revision copied into state and event, compared under shared fence lock | Rejects obsolete geometry decisions; required for a transition. |
| geofence_event.event_id | SHA-256 from asset/fence/type/source time, unique in SQL | Dedupes the same deterministic event; does not replace state loading. |
| geofence_event.type | Confirmed ENTER/EXIT from engine | Business arrival/departure, never raw circle proximity. |
| outbox.topic / msg_key / payload | Transaction saves destination, vessel/fence routing key and JSON event/change | Relay filters its topic and sends the stored message, not a recomputed event. Required. |
| outbox.published_at | NULL on insert; relay writes time after acknowledged batch publication | NULL is retriable pending work; a crash before marking can resend successful publications. |
| fence.version / deleted | API increments revision and records soft deletion in an edit transaction | Broadcast/reconciliation compares revision; deleted active state disappears without EXIT. |

## Alternatives considered and trade-offs

Two-phase commit or Seata would coordinate stores but add infrastructure and recovery complexity. Kafka exactly-once does not encompass MySQL/Redis side effects. Change-data capture could publish committed rows without polling, but adds a connector and operational machinery. The polling outbox is transparent and testable. SKIP LOCKED grants exclusive ownership of a row, not per-asset order: a later unlocked row can publish first. Single-leader and key-sharded relays could preserve ordering; this implementation chooses a downstream timestamp guard, sacrificing older live transitions that arrive late.

## Failure modes and how each is handled

Before the transaction: no durable change; rebuild and replay. After commit before offset: reload confirmed state and reject a duplicate transition. After Kafka acknowledgement before mark: pending row is resent; consumers dedupe event_id. Mid-batch SQL failure rolls back state, events and outbox together; mutated memory is discarded rather than repaired. A concurrent fence edit may let a transition confirm against the previous geometry for up to the one-minute window that requirement N5.1 allows ([ADR 039](../decisions/039-accept-previous-geometry-for-a-minute.md)); the event records the evaluated fence version. Fence notifications lost during downtime are reconciled; deletion deliberately produces no departure.

## Problems encountered and how they were solved

53b8926 adds confirmed-state loading and cache discard/rebuild, closing the different-confirmation-timestamp replay gap. 3c99787 adds atomic fence change publication. 733f4a1 separates relay ownership after the design showed row locks did not establish event order. 14d61d4 replaces serial send waits with batch acknowledgements and one marking UPDATE. ae4bcc1 uses primary-key INSERT IGNORE instead of a race-prone absence-select. These failure boundaries were exercised by injected failures, not production incident reports.

## Evidence: tests by name, measured numbers with conditions

ConsistencyIT.crashAtEachBoundaryThenRestartProducesOneDatabaseAndOneDeduplicatedEvent covers BEFORE_TX, AFTER_TX_BEFORE_OFFSET_COMMIT and AFTER_PUBLISH_BEFORE_MARK with Kafka/MySQL/Redis containers. restartInsideDoesNotProduceAnotherEnterAtALaterPositionTimestamp and databaseFailureMidBatchRollsBackAndSameEngineRetryRebuildsFromMysql cover replay/cached state. conditionalStateWriteRejectsOlderAndRepeatedTransitions, concurrentRelaysSkipRowsLockedByAnotherInstance, relayEnqueuesWholeBatchBeforeWaitingAndMarksWithOneUpdate and failedBatchAckLeavesAllRowsPendingEvenIfAnEarlierSendSucceeded verify boundaries. See [historical verification](../verification/legacy-project-reference.md).

## Known limits and what production would need

Delivery is at-least-once, not globally ordered or distributed exactly-once. Outbox cleanup is out of scope, so rows grow. Reconciliation repairs index drift but does not invent historical transitions for edited geometry. Database and Kafka demo deployment are not highly available. A production event history may require a true commit-log offset and retention policy.

## Likely interview questions with short, precise answers

**Q: Why are deterministic IDs insufficient?**

A: Replay can confirm later and generate another timestamp/hash.

**Q: What is authoritative?**

A: MySQL confirmed state, not the partition cache or Redis.

**Q: Why one SQL load per batch?**

A: Unseen assets need confirmation before evaluation without one query per point.

**Q: Why drop memory after failure?**

A: It may contain transitions the database rolled back; rebuild from durable rows.

**Q: What is atomic?**

A: State, event and pending publication in one MySQL transaction.

**Q: What does SKIP LOCKED guarantee?**

A: Two relays do not claim the same locked row, not publication order.

**Q: Can a message be sent twice?**

A: Yes; a publish-before-mark crash intentionally resends it.

**Q: Why lock fences?**

A: A transition must correspond to active geometry/version at its transaction boundary.

**Q: What if a fence is deleted inside?**

A: Drop cached state and emit no synthetic EXIT.

**Q: Why not CDC?**

A: Polling keeps this experiment simple; CDC is a different operational trade-off.
