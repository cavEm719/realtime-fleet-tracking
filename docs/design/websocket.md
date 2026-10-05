# Fan-out with bounded per-client delivery

## What: the problem in plain words, with a concrete example

Many fleet dashboards need the same company-wide positions. A client that stops reading must not block other dashboards or make the gateway keep unlimited data. Positions can be replaced by newer values; arrivals and departures need immediate distinct delivery.

## Why: requirements and constraints that drove the design

Subscriptions are tenant-wide or selected assets. Registry reads happen per position, while edits are relatively rare. Each connection needs its own bounded queue and send deadline. Company-wide frames contain identical data, so duplicating maps and JSON per recipient wastes heap and CPU.

## How: the design, with pointers to key code (file:line), SQL, Lua, proto

The gateway validates subscription ownership. Immutable subscriber sets are published through concurrent maps; writers replace a set with ConcurrentHashMap.compute, which serializes updates per asset and per company ([ADR 040](../decisions/040-per-key-atomic-subscription-updates.md)), replacing 64 explicit shard locks. targets() reads published sets without a global lock or allocating their union. Delivery avoids double sends for overlapping company/asset subscription.

Incoming company positions update one pending map per tenant. A scheduled flush builds and serializes one immutable frame per dirty tenant and enqueues that same payload into each company subscriber's mailbox. Per-asset subscriptions retain dirty-session latest-position coalescing. Fence events bypass coalescing, check a bounded event-ID LRU and a last position_ts per asset/fence, then enqueue immediately. A session's sender serializes actual I/O; buffer byte accounting and active-send timing terminate overflow/slow transports. Close work is isolated too.

[SubscriptionRegistry.java:1](../../gateway/src/main/java/dev/fleet/gateway/SubscriptionRegistry.java#L1); [GatewayHub.java:238](../../gateway/src/main/java/dev/fleet/gateway/GatewayHub.java#L238); [ClientSession.java:48](../../gateway/src/main/java/dev/fleet/gateway/ClientSession.java#L48); [GatewaySettings.java:1](../../gateway/src/main/java/dev/fleet/gateway/GatewaySettings.java#L1).

## Alternatives considered and trade-offs

STOMP would add standard destinations and broker semantics but would not remove the need for this bounded per-session policy and coalescing. Raw JSON WebSocket makes the protocol small. An unbounded async executor merely moves the backlog elsewhere. Copy-on-write makes subscription storms more expensive; it favors steady position traffic. Shared frames save serialization while each session still owns byte limits, ordering checks and transport I/O.

## Failure modes and how each is handled

A blocked sender fills its own mailbox and is terminated; normal sessions keep separate queues. Old/equal event timestamps are dropped even with a different ID, protecting displayed state from relay reordering. That guard may suppress a legitimate older event: it is a state-ordering policy, not a complete activity log. Redis positions are transient and coalesced, so intermediate observations are intentionally omitted. Subscription switches clear stale pending per-asset values.

## Problems encountered and how they were solved

9755193 adds bounded session mailboxes to keep slow I/O off the publisher. 608d17d removes the global tenant read lock and copying of subscriber sets. 3f418da removes a decorator used only for send timing and tracks timing directly. Phase 6 JFR exposed per-recipient position-map storage; b5800f1 first shares immutable keys/values. 13a5f2c then moves company coalescing/serialization to one map/frame per tenant. The historical problem list overstates the matched heap failure: the Phase 6.1 5,000-client baseline completed; 10,000 exhausted its 1 GiB heap. No failed 10,000 percentile is presented as completed capacity.

## Evidence: tests by name, measured numbers with conditions

GatewayHubTest.blockedClientOverflowsWhileNormalClientKeepsReceiving, blockedTransportCloseDoesNotBlockDeliveryToOtherClients, tenantWideRecipientsShareTheSameSerializedMessageAndKeepAssetFiltering, repeatedPositionUpdatesBecomeOneLatestEntryInOneFrame, concurrentRegistryOperationsPreserveFinalSubscriptionsAcrossManyAssets and copyOnWriteUpdatesNeverLoseOtherAssetOrTenantSubscriptions.

Matched laptop 5,000-client company-wide trials (three per build, 70-second windows, 10-minute NOAA subset at 10×, 1 GiB gateway heap, tracing off, zero 429s): live heap after forced GC 579.4→396.5 MiB, GC pauses 1,877→313 ms and pipeline p99 1,099→596 ms. GC totals include the forced collection. [Performance](performance.md) gives ranges and AWS scale-out.

## Known limits and what production would need

A single flusher remains; CPU-heavy network sends still scale with recipients. Subscription copy-on-write has storm cost. Buffers, ordering maps and event LRU are bounded/connection-local but do not offer durable delivery by themselves; [resume](resume.md) adds bounded catch-up. No browser UI, TLS/authentication hardening or unlimited-client capacity is claimed.

## Likely interview questions with short, precise answers

**Q: Why replace positions?**

A: Dashboards need the latest point; intermediate points are expendable.

**Q: Why not coalesce events?**

A: A confirmed arrival/departure has independent business meaning.

**Q: What is shared?**

A: One immutable serialized company frame, not a shared unbounded session queue.

**Q: What remains per connection?**

A: Mailbox bytes, transport sending, timestamp and event guards.

**Q: Why copy-on-write sets?**

A: Frequent publish reads avoid locking; rare edits pay allocation.

**Q: Can subscription storms cost more?**

A: Yes, immutable set rebuilds are the trade-off.

**Q: Why remove the decorator?**

A: The mailbox already serializes sends; direct timing simplifies the remaining checks.

**Q: What isolates slow clients?**

A: Independent bounded mailboxes and sender/close execution.

**Q: Did the laptop prove unchanged slow-client p99?**

A: No; median rose 424→554 ms in matched trials.

**Q: Is the timestamp guard reliable history replay?**

A: It protects latest state; older delayed events can be dropped.
