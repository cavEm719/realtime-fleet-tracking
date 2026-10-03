# Ordered ingestion and replay-safe Redis updates

## What: the problem in plain words, with a concrete example

A vessel reports its identity, coordinates and observation time. A fleet operator wants its latest location and reliable port events even when a sender retries. Suppose Kafka accepted a batch but the HTTP response was lost: sending the batch again must not move a vessel backward or permanently stop its partition because one report is malformed.

## Why: requirements and constraints that drove the design

Acceptance means Kafka acknowledged every report, not that all downstream work is complete. Vessel identity is the Kafka key so one vessel reaches one partition. Retries can duplicate accepted reports; old source observations can arrive after newer ones. Invalid data must be removed from the processing stream while temporary infrastructure failures must retain the offset for replay.

## How: the design, with pointers to key code (file:line), SQL, Lua, proto

The API validates the whole HTTP batch, establishes tenant ownership, spends quota and queues all producer sends before awaiting them under one deadline. Idempotent production with at most five in-flight requests preserves per-partition producer order; it does not sort source time. The processor decodes records in order. Before reporting a poison index, it applies the valid prefix; the error handler sends the bad record to positions.DLT and retries the remaining suffix.

Java supplies each Redis position as positionTs, longitude, latitude and encoded payload. Lua reads the latest hash's position_ts, compares the entire batch, and only then performs HSET, GEOADD and PUBLISH. It accounts for repeated assets within the same batch. Acceptance flags gate those Redis effects only: every decoded position still reaches geofence evaluation.

[IngestController.java:24](../../api/src/main/java/dev/fleet/api/IngestController.java#L24); [TenantConfigCache.java:36](../../api/src/main/java/dev/fleet/api/TenantConfigCache.java#L36); [PositionListener.java:70](../../processor/src/main/java/dev/fleet/processor/PositionListener.java#L70); [KafkaConfiguration.java:22](../../processor/src/main/java/dev/fleet/processor/KafkaConfiguration.java#L22); [positions.lua:1](../../processor/src/main/resources/lua/positions.lua#L1).

## Alternatives considered and trade-offs

A separate dedupe write followed by latest/GEO writes could acknowledge a timestamp without its location. One script removes that application crash gap. Kafka transactions alone cannot atomically commit Redis and MySQL. The design uses replay plus conditional writes rather than promising distributed exactly-once delivery. Flat arguments avoid Redis JSON decoding and duplicate Java validation. A cache avoids repeated tenant/ownership SQL reads; committed ownership is cached only after registration succeeds.

## Failure modes and how each is handled

A malformed middle record is non-retryable; DLT publication must succeed before recovery advances its offset. Redis/connection failures use capped exponential delay without committing success. An uncertain producer timeout returns 503 and the client retries the whole batch. Token spend is not refunded, so a retry is charged again. Redis does not roll back scripts after runtime errors: trusted Java-validated coordinates and application-owned key types/permissions are preconditions. Read-before-write is not a guarantee against arbitrary OOM, wrong-type or permission changes.

## Problems encountered and how they were solved

The separate dedupe/pipeline gap was identified before the first committed implementation: 39ba22a already has one script, not an observed production loss. 0601b2a replaces unlimited retries for malformed records with indexed DLT recovery. 5c7b2ac and d1b14df replace per-record acknowledgement waits with enqueue-all and an overall deadline, including time spent enqueueing. 59e5184 removes JSON decoding and the separate last-ts key. a219930 caches tenant config and committed ownership. 4f848ba adds get() before compute(), avoiding a bin lock on hot cache hits. These are implementation/design corrections, not measured outage counts.

## Evidence: tests by name, measured numbers with conditions

PositionListenerTest.appliesTheValidPrefixBeforeReportingThePoisonRecordIndex, doesNotCommitOnRedisFailure and rejectsSpoofedTenantAndWrongKafkaKey cover local branches. ProcessorKafkaIT.replaysAfterRedisSucceededButOffsetWasNotCommittedAndSurvivesRebalance exercises real Kafka/Redis replay. The poison-record integration scenario is in PoisonMessageIT. See the retained [phase reference](../verification/legacy-project-reference.md). AWS throughput conditions and counters are in [performance](performance.md); unit tests do not establish capacity.

## Known limits and what production would need

The script assumes one Redis node and trusted key ownership; Cluster needs all script keys in the same hash slot. Kafka's single broker in the demo is not highly available. Cached ownership assumes stable registration. Production needs authenticated tenant identity, capacity controls and DLT operations. No cross-database exactly-once claim is made.

## Likely interview questions with short, precise answers

**Q: What does HTTP success mean?**

A: All Kafka sends were acknowledged; processing continues asynchronously.

**Q: Why key Kafka by MMSI?**

A: All observations of a vessel reach one partition.

**Q: Does Kafka order observation time?**

A: No. It orders accepted records; source timestamps are checked separately.

**Q: Why enqueue before waiting?**

A: Several requests can progress in parallel under one overall deadline.

**Q: Does idempotent Kafka remove HTTP duplicates?**

A: No; retrying an application batch can produce new records.

**Q: Why Lua?**

A: It closes the application gap between dedupe, latest location, GEO and publication.

**Q: Does Redis undo an errored script?**

A: No. Inputs and trusted key invariants must make the write phase valid.

**Q: Why does geofence see duplicates?**

A: Redis acceptance cannot suppress a replay whose MySQL work never committed.

**Q: What happens to poison data?**

A: Indexed recovery publishes it to the DLT and continues the valid records.

**Q: Why is a 503 retry charged twice?**

A: Quota is spent before publishing; refunding uncertain acceptance is deliberately omitted.
