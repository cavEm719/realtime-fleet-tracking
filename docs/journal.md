# Engineering journal

The project accepts vessel reports, confirms port movements and delivers them to fleet clients. This journal records how that chain and the fleet-question service evolved. It distinguishes an implementation correction, an injected fault test and a paid experimental failure. No production incident rate is inferred from these records. The chronological index at the end includes every commit retained at the start of Phase 8; design records and linked verification reports explain the mechanisms and evidence.

## Ingestion and early processing

The first committed core, 39ba22a, already merges Redis dedupe/latest/GEO/publication into one script. A separate-write crash gap was a rejected design, not a committed incident. Early tests in 8b62c01 exercise crash replay and trusted input handling. A malformed Kafka record could otherwise be retried indefinitely: 0601b2a introduces indexed DLT recovery so the prefix is applied before recovery and the suffix continues. 5c7b2ac queues producer sends as a batch; d1b14df includes enqueue time in the deadline. 59e5184 removes redundant Lua parsing/type/ACL work and the separate last-ts key. a219930 and 4f848ba cache committed ownership/config and avoid locking a hot tenant key on cache hits. Quota tokens remain spent before Kafka, including uncertain-acceptance retries. [Ingestion design](design/ingestion.md).

## Port decisions and persistence

2635476 and e2296d6 introduce grid candidates, inner/outer radii and partition-owned hysteresis. Redis acceptance never gates geofence evaluation. ded82c6 publishes copy-on-write grid snapshots. In Phase 2 confirmations only logged; 53b8926 loads MySQL confirmed state before unseen asset evaluation, then saves collected transitions in one transaction. This addresses later-timestamp re-confirmation after transient candidate loss. Failed persistence discards cache instead of repairing it. The writer locks/validates active fence versions, while 3c99787 makes edits and broadcast publication atomic. 733f4a1 separates outbox topics and explicitly acknowledges SKIP LOCKED publication reordering; 14d61d4 queues publication before one marking update. ae4bcc1 relies on primary-key INSERT IGNORE. Injected crashes test transaction/offset/publication boundaries, not a production outage. [Consistency design](design/consistency.md).

## Connection delivery and reconnect

9755193 adds tenant-scoped bounded mailboxes so blocked transports cannot stall unrelated connections. 608d17d replaces global publish locks/copies with immutable sets; 3f418da removes redundant decorator timing. Original Phase 6 profiles show boxed keys/values in recipient maps and unsuccessful high-company-fan-out attempts; b5800f1 shares immutable values, but the rerun then exposes generator memory pressure. 59f9508 fixes generator retention and separates pipeline receipt timing from emittedAt. 13a5f2c shares company maps/frames once, with matched repeated heap/GC/latency evidence. The refreshed 5,000 baseline completes; 10,000 fails, rather than a claimed 5,000 Phase 6.1 heap failure.

ac1c316 restores missed durable events and buffers concurrent live events. d6e8910 replaces event-time resume with creation time/ID and overlap. Seeding every old overlap row was rejected because it would hide late commits; only tenant-validated client-confirmed IDs seed dedupe. cd6cd9c registers exact Redis channels only for active local tenants. [Delivery](design/websocket.md), [resume](design/resume.md).

## Copilot boundary and tracing

7ce5a65 begins bounded tenant tools and DRAFT-only rules. 97786c4 removes blocking model I/O under synchronized due to Java 21 pinning risk; starvation itself was not measured. b7bec69 bounds memory and c743f38 gives a normal tool-cap reply distinct from quota 429. 165d05a separates copilot from ingest and exposes API-owned data over gRPC; e4fdab3 replaces JSON strings with typed proto. 964d451 preserves last-known policy on refresh error. 45b1dbb carries outbox traceparent and record-linked batches; 045943a fixes observation scope overriding restored parents. Multi-instance local traces are retained by 2aca0a3. [Copilot](design/copilot.md); the tracing design was later removed (ADR 041).

## Laptop measurement corrections

97c023c/7f24c59 preserve Phase 6 failures/raw JFR. The initial timestamp included source batching and whole-second quota retries, so old p99 could not be compared to pipeline-only latency. 59f9508 fixes it; latency cases verify zero quota retries. fd16a01 fixes parsing GC labels with spaces. 1e4df3e increases benchmark-only nginx connection headroom after exploratory startup/connection limits. 72a3ed9 records Phase 6.1: successful serialize-once comparison, failed 10,000 baseline, reduced flapping without labels, and worse slow-client laptop p99. Two processors on one laptop did not establish throughput gain. [Performance](design/performance.md).

## AWS provisioning and measurement

185fa60 records the free-plan launch failure and complete cleanup before paid-plan continuation. Initial live bootstrap needed the bundled CLI (818653b), executable benchmark JARs (554302d) and correctly quoted MySQL readiness (21a9351). 553c51d fixes a hardwired processor consumer group that interfered with scenario resets; 89bfc0f refreshes groups after partial deletion. Resource sampling gains host clocks, timestamped container samples and ENA counters. Counter resets, missed scrapes and untimestamped startup peaks are kept unknown rather than turned into clean results (d2783b9, 67be39d).

ffab8ae records separate-host AWS trials. Input stays near the same rate when processors increase; API CPU is the observed shared limit. Gateway scale-out helps the matched 5,000 case; the old smoke p99 cause remains unresolved. 6e7f977 records copied results, Terraform destruction and direct audit. [AWS matrix](verification/aws-gate2-matrix.md).

## Bedrock/deployment failures and recovery

aa57800 records Claude agreement payment errors; the evaluation uses DeepSeek/gpt-oss rather than another Marketplace subscription. b3248ae fixes an empty temporary Docker config hiding normal buildx lookup; normal configuration stays intact. 2c2753b requires local ARM build/start and forced verdict tools. An ECR-only export tried to read uncreated host outputs; fde7bab adds a repository-only exporter. 2b9f7d5 rehearses plans/exports/push/deploy without paid hosts. Separate earlier failed attempts retain evidence and cleanup.

Round 3 provisioning loses an operator session before hosts exist (b13f829); after renewal partial resources are destroyed/audited before recreation, without resetting the deadline. Later startup succeeds with an obsolete image JAR: missing UTC fields/outage behavior reveal it. c3f901e adds hash verification; interrupted prefixes remain excluded but costed. Corrected artifacts are deployed within the bounded repair window. bab6bba records copied archives, empty active-resource audit and protected cost estimate under the unchanged deadline. No invoice or credits are inferred. [Round-3 retained report](verification/aws-copilot-round3-results.md).

## Evaluation, independent checks and transparent queries

9f06503 measures task facts/leaks/effects instead of mere tool selection. Round 1 identifies summary/name/time gaps; a3514a6 adds bounded tools with SQL totals. GPT's first matrix is only a partial prefix. Round 2 completes both models; failure triage preserves wrong arguments, unknown usage and evidence. A full independent inspection of 240 answers finds six checker false-fail patterns. 2c19ef8 revises supported rules and preserves original/regraded scores, but its 240/240 agreement uses the same labels and is in-sample. Two corrected question definitions become task version 2 without replacing old results.

c6abbc3 returns ISO UTC strings and stops further dependency tools after an outage; drafts explicitly never notify. Round 3 adds out-of-sample output: frozen rules still falsely fail 10 of 18 failures, while sampled successes have no false passes. d8e41c8 freezes the checker with four known patterns and estimates corrected totals; independent rounding/association errors remain despite a zero explicit-UTC diagnostic. Neither retained scores nor labels are overwritten.

2114095/6af5dc3 make a strictly validated ID-only spec supply parameterized SQL and code-rendered basis/results through receipts. Names come from owned rows; zero/multiple matches require clarification. Distinct totals are independent of overlapping groups. b82722e adds a separate unrun query-transparency model suite, leaving the frozen answer checker untouched. [Evaluation](design/evaluation.md), [query transparency](design/query-transparency.md).

## Requirements and simplification

The design had grown from a list of mechanisms to learn. b62bef6 adds [requirements](requirements.md) with scale, latency and availability numbers and checks every design against them. A first pass marked several mechanisms redundant; reading the code reversed three verdicts (event-ID dedupe is the only guard on resume, gateway virtual threads carry per-connection senders, the resume protocol is a placement choice). A separate single-process experiment that wrote every position to MySQL failed at 100 positions/s per company with commit timeouts, which showed that Redis latest positions keep per-position writes off MySQL.

eb942c5 replaces the geofence grid with per-company lists; 31e705d removes the fence share lock and version rejection (N5.1 accepts a minute of old geometry); dc69994 returns the API and processor to platform threads; 705981f removes tracing; fa65ef3 replaces subscription shards with per-key atomic updates. 1bcebff removes the Java copilot. 90eb49c removes alert drafts, serves copilot RPCs from a read-only replica pool with a 60-second freshness check and returns API-rendered presentations; fc59281 adds the GTID replica. 3a6d8ac adds the Python copilot; 84697df adds the local DeepSeek evaluation and answers API outages without the model after the first pilot returned HTTP 503 for them. [ADRs 037–045](decisions/README.md).

## Complete chronological index

Commit subjects are a navigation index, not independent validation of their claims. Source/tests and verification reports substantiate the interpretations above. Dates are local Git commit dates; individual cloud trial timestamps are in manifests.

| Commit | Date | Change recorded in history |
|---|---|---|
| `39ba22a` | 2026-10-07 | Scaffold modules and implement tenant-scoped ingestion core |
| `8b62c01` | 2026-10-07 | Test crash replay, Lua prevalidation, tenant isolation and rate limiting |
| `4c97dc3` | 2026-10-07 | Document Phase 1 operation, Redis safety rules and future geofence boundary |
| `0601b2a` | 2026-10-07 | Recover invalid batch records to DLT and retry infrastructure failures |
| `a219930` | 2026-10-07 | Cache tenant configuration and committed asset ownership on ingest |
| `5c7b2ac` | 2026-10-07 | Enqueue ingest batches before awaiting all Kafka acknowledgements |
| `59e5184` | 2026-10-07 | Use flat Redis batch arguments and dedupe in the latest-position hash |
| `8c1cc19` | 2026-10-07 | Simplify token bucket Lua to trusted application-owned state |
| `d1b14df` | 2026-10-07 | Honor the overall batch deadline during Kafka enqueueing |
| `9be9a4e` | 2026-10-07 | Expand Java and Maven formatting and split replayer methods |
| `b488f70` | 2026-10-07 | Document DLT recovery, cache behavior and retry quota trade-offs |
| `4f848ba` | 2026-10-07 | Use lock-free tenant cache hit path |
| `2635476` | 2026-10-07 | Add tenant-scoped circular fence grid index |
| `e2296d6` | 2026-10-07 | Evaluate full position batches with partition-owned geofence hysteresis |
| `853ef96` | 2026-10-07 | Seed demo US port fences and document CORE 2 replay behavior |
| `ded82c6` | 2026-10-07 | Use immutable copy-on-write fence grid snapshots |
| `c0b9ec0` | 2026-10-07 | Keep fence domain and settings free of persistence details |
| `e876938` | 2026-10-07 | Share transactional outbox relay with row claims and test-only fault hooks |
| `53b8926` | 2026-10-07 | Restore confirmed state and persist geofence batches with broadcast cache repair |
| `3c99787` | 2026-10-07 | Add tenant-scoped fence CRUD with atomic change outbox |
| `917b0a4` | 2026-10-07 | Document MySQL state recovery, outbox delivery and verified fault scenarios |
| `733f4a1` | 2026-10-07 | Assign outbox topic ownership and explain downstream ordering guard |
| `14d61d4` | 2026-10-07 | Publish outbox batches asynchronously and mark with one update |
| `ae4bcc1` | 2026-10-07 | Use primary-key INSERT IGNORE for missing confirmed state |
| `ee974db` | 2026-10-07 | Share the fence event wire record with the gateway |
| `9755193` | 2026-10-07 | Implement tenant-scoped WebSocket gateway with batching and ordering guards |
| `608d17d` | 2026-10-07 | Publish immutable subscription sets for lock-free delivery |
| `3f418da` | 2026-10-07 | Enforce session send limits directly in the bounded mailbox |
| `240b0a9` | 2026-10-07 | Index outbox polling by topic and pending timestamp |
| `e775bc9` | 2026-10-07 | Add compatible Spring AI providers and tenant LLM token buckets |
| `7ce5a65` | 2026-10-07 | Implement tenant-scoped copilot tools with bounded execution and draft rules |
| `4ccd3c1` | 2026-10-07 | Verify copilot isolation and limits and document manual model evaluation |
| `97786c4` | 2026-10-07 | Avoid Java 21 carrier pinning with deadline-bounded conversation locks |
| `b7bec69` | 2026-10-07 | Bound per-instance conversation memory with leased LRU entries and idle expiry |
| `c743f38` | 2026-10-07 | Return a normal copilot reply on tool cap while retaining rejection metrics |
| `f107d55` | 2026-10-07 | Add ordered replay lanes and reproducible load-test tooling |
| `b5800f1` | 2026-10-07 | Share immutable position keys and values across gateway recipients |
| `97c023c` | 2026-10-07 | Document measured Phase 6 results and benchmark limitations |
| `7f24c59` | 2026-10-07 | Preserve raw scenario measurements, JFR profiles and verification output |
| `165d05a` | 2026-10-07 | Extract copilot behind a tenant-scoped deadline-protected gRPC boundary |
| `45b1dbb` | 2026-10-07 | Trace Kafka batches, durable outbox publication and asynchronous gateway delivery |
| `47ee6ea` | 2026-10-07 | Preserve API nearby-query radius validation |
| `d212149` | 2026-10-07 | Add a two-processor two-gateway Compose profile with nginx and copilot |
| `ce6ee65` | 2026-10-07 | Record Phase 5.5 verification and blocked Compose runtime check |
| `94068e0` | 2026-10-08 | Fix nginx IPv4 health check and share upstream connection counts |
| `045943a` | 2026-10-08 | Keep restored outbox traces as parents of Micrometer Kafka observations |
| `2aca0a3` | 2026-10-08 | Record successful Phase 5.5 verification and complete multi-instance traces |
| `964d451` | 2026-10-08 | Serve last known tenant policy when refresh is unavailable |
| `e4fdab3` | 2026-10-08 | Replace JSON RPC results with typed bounded protobuf responses |
| `6c56e87` | 2026-10-08 | Keep compact verification evidence and exclude raw process logs |
| `a39335f` | 2026-10-08 | Document internal gRPC service authentication as out of scope |
| `1b5d695` | 2026-10-08 | Record verification of the Phase 5.5 review fixes |
| `59f9508` | 2026-10-08 | Measure pipeline latency from API receipt and bound load generator retention |
| `13a5f2c` | 2026-10-08 | Serialize company-wide position frames once per tenant |
