# Stable port entry and exit decisions

## What: the problem in plain words, with a concrete example

A vessel near Los Angeles alternates just inside and outside a port circle because location reports jitter. Turning every crossing into an arrival or departure would create misleading activity. The engine takes newer observations, finds nearby owned port areas and confirms a crossing only after sustained evidence.

## Why: requirements and constraints that drove the design

AIS means Automatic Identification System: vessel broadcasts contain positions and source times, often minutes apart. A port uses an inner enter radius and a larger exit radius. Defaults require three consecutive qualifying observations spanning 120 seconds of source time; point count and duration are both required. These are configurable demo choices, not a trained classifier or official port boundary.

## How: the design, with pointers to key code (file:line), SQL, Lua, proto

A fence is a tenant-owned circular port area loaded from MySQL at startup. The index keeps one immutable list of active fences per company. A company watches at most about 20 ports ([requirements](../requirements.md) §2), so each position is checked against every port of its own company with a spherical distance calculation, about 50,000 calculations per second at the 2,500 positions/s peak. A lookup reads one snapshot through a volatile reference; rare put/remove operations rebuild it under a writer lock. An earlier grid index ([ADR 038](../decisions/038-per-company-fence-lists.md)) was removed as unnecessary at this scale.

Partition-owned maps hold asset last-ts, confirmed cache and candidate count/start time. Old/equal timestamps do not advance candidates. A contradictory point cancels a candidate; the band between enter and exit radii retains confirmed state. Confirmations collect transitions; persistence is a separate batch step. Revocation before commit and partition loss discard the owning maps cheaply.

[FenceIndex.java:7](../../processor/src/main/java/dev/fleet/processor/geofence/FenceIndex.java#L7); [GeofenceEngine.java:38](../../processor/src/main/java/dev/fleet/processor/geofence/GeofenceEngine.java#L38); [GeofenceSettings.java:1](../../processor/src/main/java/dev/fleet/processor/geofence/GeofenceSettings.java#L1); [V3__demo_port_fences.sql:1](../../api/src/main/resources/db/migration/V3__demo_port_fences.sql#L1).

## Alternatives considered and trade-offs

Naive mode confirms an immediate boundary change and provides a replay baseline. Hysteresis reduces jitter but delays detection. A spatial index, database or polygon library would be needed for thousands of areas per company or for polygons; a few circular ports per company need only a distance check. Copy-on-write exchanges rare write cost and temporary snapshot allocation for lock-free position reads.

## Failure modes and how each is handled

On crash or rebalance, candidate progress and per-asset last-ts intentionally disappear. New ownership reloads durable confirmed rows before evaluation; it does not infer confirmation from Redis. Startup fails if fences cannot be read. A deleted fence removes associated state without inventing an EXIT. Dateline wrapping is covered; geographic circles remain approximations.

## Problems encountered and how they were solved

e2296d6 makes full decoded batches independent of Redis flags and owns transient state by partition. ded82c6 removes the per-position index read lock using immutable snapshots. 53b8926 replaces Phase 2's logging-only confirmation with durable load/write boundaries. These changes address replay and contention by design; no measured read-lock contention percentage was retained.

## Evidence: tests by name, measured numbers with conditions

FenceIndexTest.lookupIsTenantScoped, singleFenceCanBeReplacedAndRemovedWithoutDisturbingAnotherFence and readersKeepTheirSnapshotWhileWritersReplaceIt. GeofenceEngineTest.jitterCreatesNoHysteresisTransitionsButSeveralNaiveTransitions, cleanEntryAndExitConfirmOnceEvenAfterJumpingFarAway, pointCountAndEventTimeAreBothRequiredAcrossBatches and revokeDropsTimestampCandidateAndConfirmedStateOnlyForRevokedPartition.

On the same fresh-state NOAA four-hour West Coast replay (261,255 observations), naive mode produced 8 flaps versus 3 with hysteresis; flaps mean adjacent EXIT→ENTER within 30 minutes or ENTER→EXIT within 10 minutes. This is stability evidence without labeled truth; [performance](performance.md) preserves counts and limits.

## Known limits and what production would need

No labeled arrivals establish precision/recall. Port circles and sparse reports can delay or miss brief visits. Production would need domain-approved geography and reporting-frequency-specific thresholds; no such behavior is claimed here.

## Likely interview questions with short, precise answers

**Q: What is hysteresis here?**

A: Different enter/exit radii plus consecutive-point and elapsed-time confirmation.

**Q: Why require both N and T?**

A: Many closely spaced points and one widely spaced point should not each independently confirm.

**Q: Which clock drives T?**

A: The vessel observation timestamp, not replay wall time.

**Q: Why no spatial index?**

A: At most about 20 ports per company: checking them all is about 50,000 distance calculations per second at peak.

**Q: Why copy-on-write?**

A: Positions are frequent and fence edits are rare.

**Q: What survives rebalance?**

A: MySQL confirmed state; candidates and last-ts cache do not.

**Q: Why not use Redis dedupe?**

A: A replay may need to finish durable work despite Redis having succeeded.

**Q: Does fewer flapping prove accuracy?**

A: No; it measures stability on one unlabeled replay.

**Q: What if a fence disappears?**

A: Drop its state without creating a fabricated departure.
