# Company scope and quota enforcement

## What: the problem in plain words, with a concrete example

Two companies may operate vessels near the same port. A dashboard or model query for one company must not reveal the other's vessels. A noisy sender must also spend only its own quota. The demo assigns vessel identities deterministically to three tenants so isolation can be tested without external account infrastructure.

## Why: requirements and constraints that drove the design

Identity must be established outside model arguments. API SQL, Redis keys, subscriptions and tool requests all need the same scope. Ingest positions/s and copilot questions/min (default 100 per company, [requirements](../requirements.md) N6.2) are separate policies and buckets. Registration must become visible in caches only after its SQL commit.

## How: the design, with pointers to key code (file:line), SQL, Lua, proto

Incoming request scope is checked server-side. Ingest validates MMSI-to-tenant assignment and batches; asset registration uses unique constraints and confirmed ownership caches. Tenant configuration has a lock-free get hit path and a short expiry. Redis token buckets use application-supplied capacity/refill and execute one atomic read/calculate/write operation per charge.

The Python copilot keeps the tenant in its per-question state and sets it in every gRPC request; no tool schema has a company parameter. Conversation history keys include the tenant, so a conversation ID cannot read another company's history. The API validates selected fence/asset ownership before SQL or tenant-keyed Redis reads. Gateway handshakes bind sessions to one tenant, reject foreign assets, and add/remove exact Redis channels only while local sessions exist. Fence Kafka messages for absent local tenants are dropped before per-session work; Kafka remains instance-wide broadcast.

[TenantAssignment.java:1](../../common/src/main/java/dev/fleet/common/TenantAssignment.java#L1); [AssetRegistry.java:1](../../api/src/main/java/dev/fleet/api/AssetRegistry.java#L1); [token-bucket.lua:1](../../common/src/main/resources/lua/token-bucket.lua#L1); [tools.py:1](../../copilot/fleet_copilot/tools.py#L1); [PositionSubscriber.java:1](../../gateway/src/main/java/dev/fleet/gateway/PositionSubscriber.java#L1).

## Alternatives considered and trade-offs

Separate databases/topics per company offer stronger administrative isolation but substantially more infrastructure. Shared tables and explicit tenant predicates keep the demo small. Dynamic exact Redis subscriptions save irrelevant traffic; Kafka per-tenant topics or partition-aware routing are the next larger-scale alternative. Bucket refunds after uncertain send acceptance would complicate accounting, so retries spend again.

## Failure modes and how each is handled

Foreign tool IDs are rejected before access; tenant is absent from generated tool schemas. Foreign seen IDs cannot suppress local history. Subscribe/unsubscribe callbacks reconcile actual active demand so a last-close/first-open race does not leave a wanted channel removed. If the copilot cannot read the company's quota because the API is down, it answers that data is unavailable without calling the model; there is no stale-policy fallback. Authentication headers/parameters in this demo are not a production identity provider.

## Problems encountered and how they were solved

a219930 and 4f848ba remove repeated SQL and hot-key cache locking. 8c1cc19 removes Redis corruption/ACL/type checks under trusted-key assumptions. cd6cd9c narrows previously global Redis traffic to active tenant channels and tests lifecycle races. 964d451 had added a last-known policy fallback; the Python rewrite removed it (ADR 042). No real foreign-data disclosure was observed in the retained deterministic fixture runs; this is bounded evidence, not proof for all inputs.

## Evidence: tests by name, measured numbers with conditions

IngestIT.rejectsForeignTenantWithoutRegisteringOrSpendingTokens and llmBucketUsesMinuteRefillAndDoesNotSpendOtherTenantOrIngestTokens; CopilotTest.generatedSchemasNeverExposeTenantOrContext and injectionCannotSelectAnotherTenantOrReadItsAsset; GatewayHubTest.crossTenantRequestIsRejectedBeforeAnySubscriptionIsAdded. Dynamic channel lifecycle coverage is in TenantSessionScopeTest. Retained round-3 data-leak counts are 0 for each model on 120 synthetic runs; strict identifier echo counts differ, as [evaluation](evaluation.md) explains.

## Known limits and what production would need

Internal gRPC has no service authentication; production would use mTLS or signed service tokens so an arbitrary caller cannot assert a tenant. Demo MMSI hashing is not real ownership proof. Rate tokens are spent before publishing and uncertain 503 retries are charged twice. The shared Redis LLM bucket is the documented infrastructure exception to copilot data ownership.

## Likely interview questions with short, precise answers

**Q: Where does model tenant scope come from?**

A: Server-side ToolContext, never a model parameter.

**Q: Can a tool choose another tenant?**

A: Its schema has no tenant field and the API validates owned IDs.

**Q: Is a tenant parameter authentication?**

A: No; authenticated identity is a production requirement.

**Q: Why cache ownership?**

A: Confirmed registrations avoid repeated SQL on every observation.

**Q: Why publish caches after commit?**

A: A rolled-back registration must not be remembered as valid.

**Q: Are ingest and LLM buckets shared?**

A: They use the same mechanism but distinct tenant keys/policies.

**Q: What happens after a 503 retry?**

A: The new attempt spends quota again.

**Q: Why dynamic Redis channels?**

A: Instances need positions only for companies with local sessions.

**Q: Does Kafka traffic also narrow?**

A: No; only early filtering is implemented there.

**Q: Does zero fixture leaks prove complete security?**

A: No; it proves no detected foreign data in that fixed test set.
