# Measuring the pipeline and identifying its limits

## What: the problem in plain words, with a concrete example

A faster replay is useful only if locations arrive promptly and lag drains. The benchmark follows acknowledged source positions, processor consumption and dashboard receipt while sampling the machines. It deliberately retains failed runs, so a connection count or partially collected histogram is not mistaken for sustained delivery.

## Why: requirements and constraints that drove the design

The first latency clock started before source batching and quota retry, so much of its tail measured the generator. The revised primary metric is receipt minus server ingestedAt; emittedAt remains a secondary end-to-end metric. Latency trials must be below quota with zero 429s. Processor scaling must separate shared-laptop contention from separate-host behavior. Headline values are finite trial observations, not production SLAs.

## How: the design, with pointers to key code (file:line), SQL, Lua, proto

The replayer sorts a NOAA day subset by source time and hashes MMSI to sequential sending lanes. Maximum-rate mode ignores source pacing but keeps vessel order; fixed offer mode bounds global rate. JDK HttpClient WebSocket clients run on virtual threads, rotate tenants and choose company/per-asset scope. Slow clients stop requesting frames. The generator retains bounded histogram bins, streams timestamps and records missing/negative values.

Before/after Prometheus scrapes give counters/timers; lag is sampled per member/partition, and zero lag after producer completion closes the drain window. One-second AWS host samples record all-core CPU, container one-core-percent CPU, network, Redis main-thread CPU and Lua commandstats. Counter resets/missing samples remain unknown. Median/min/max are across trial metrics, not pooled percentiles. Latency histogram merging across two generators happens within each AWS trial.

[Replayer.java:1](../../replayer/src/main/java/dev/fleet/replayer/Replayer.java#L1); [IngestTimingFilter.java:1](../../api/src/main/java/dev/fleet/api/IngestTimingFilter.java#L1); [phase61.py:1](../../bench/phase61.py#L1); [report-aws.py:1](../../bench/report-aws.py#L1).

**Laptop setup:** Apple M5, 10 cores, 16 GiB, macOS 26.4, JDK 21.0.12.1; infrastructure Docker VM about 8 GiB, Kafka/Redis/MySQL caps 1,536/512/1,024 MiB, no CPU quotas, Redis appendfsync always. Host API/processor/replayer heaps 512 MiB, gateway 1 GiB, one generator 3 GiB. Matched S2: 11,669 rows/3,048 vessels, ten-minute West Coast subset, 10× replay, 70-second windows, three trials at 5,000 clients and one at 10,000; tracing disabled, zero 429/503 retries. GC.run at 20 seconds; pause total includes it.

| Laptop matched company-wide case | Post-GC live heap MiB | GC pause ms | Frames/s | Pipeline p50 / p95 / p99 ms |
|---|---:|---:|---:|---|
| S2-before-5000 | 579.4 [578.5–581.5] | 1,877 [1,830–1,953] | 28,093 [27,652–29,211] | 110 [107–113] / 443 [427–561] / 1,099 [1,028–1,156] |
| S2-after-5000 | 396.5 [395.5–396.6] | 313 [273–323] | 35,447 [34,663–36,734] | 99 [98–102] / 216 [210–333] / 596 [519–642] |
| S2-after-10000 | 765.0 [765.0–765.0] | 1,496 [1,496–1,496] | 61,809 [61,809–61,809] | 208 [208–208] / 893 [893–893] / 1,273 [1,273–1,273] |

The 10,000 baseline connected then exhausted gateway heap; complete heap/latency/throughput metrics are unavailable. Original Phase 6 results, including failed company-wide cases and generator OOM, remain in the [relocated historical reference](../verification/legacy-project-reference.md).

**Phase 9 rerun after the requirements-driven simplification** (commit 3bae968, [ADR 037](../decisions/037-requirements-driven-simplification.md)): same laptop, same scenarios and settings as Phase 6.1, three trials each; the AWS figures below were measured on the earlier code (93bd5a1) and were not rerun. [Summary](../../bench/results/Phase9/summary.json).

| Laptop scenario | Phase 6.1 (before) | Phase 9 (after) |
|---|---|---|
| S1 ingest, positions/s | 27,437 [27,313–29,624] | 26,753 [26,415–27,284] |
| S1 processor drain, positions/s | 24,972 [24,919–27,643] | 24,929 [24,912–24,989] |
| S2 5,000 clients, pipeline p50 / p95 / p99 ms | 99 / 216 / 596 [519–642] | 103 / 277 / 648 [501–897] |
| S2 gateway live heap after GC, MiB | 396.5 | 398.2 |
| S2 gateway GC pause total, ms | 313 | 285 |
| S3 1,000 clients, 0% slow, p99 ms | 424 [267–434] | 444 [377–603] |
| S3 1,000 clients, 5% slow, p99 ms; slow sessions terminated | 554 [446–649]; 50/50 | 427 [399–543]; 50/50 |

All 12 trials completed with every client connected and no failed clients; latency trials had zero 429s. Medians with [min–max] over three trials. The differences are within trial-to-trial variation; the simplification neither gained nor lost measurable laptop throughput or latency. The S2 p95/p99 ranges widened. This rerun used the benchmark harness after two fixes: the lag monitor now watches the `fleet-processor` consumer group (renamed in 553c51d, which had made every drain look stuck) and the reset no longer deletes the removed `alert_rule` table.

**AWS setup:** separate us-east-1 Graviton Amazon Linux hosts; API/processor/gateway each c7g.large (two vCPU), app -Xms256m -Xmx1g in 2 GiB containers. Kafka/Redis m7g.large, MySQL r7g.large. Two c7g.xlarge generators each -Xms512m -Xmx3g in 4 GiB; nginx separate. S1: 261,255 rows/3,249 vessels, West Coast 00:00–04:00 UTC on 2023-01-01, 12 lanes/max rate. S2/S3: first 125,000 rows, 1,000 positions/s offer, 120-second client window, three tenants, company-wide, quota 250,000/s each. Three valid trials/case, tracing disabled, zero latency-scenario 429s.

| AWS case (three trials each) | Ingest positions/s | Drain-window positions/s | Pipeline p50 / p95 / p99 ms |
|---|---:|---:|---|
| S1-processors-1 | 40,899 [39,850–41,042] | 29,690 [25,964–30,337] | Not measured (no clients) |
| S1-processors-2 | 40,879 [40,225–41,521] | 26,034 [25,776–30,928] | Not measured (no clients) |
| S1-processors-4 | 41,304 [40,880–41,700] | 30,425 [26,330–30,721] | Not measured (no clients) |
| S2-company-10000-gateways-2 | 999 [999–1,000] | 964 [962–964] | 1,286 [1,201–1,348] / 2,404 [2,279–2,462] / 2,809 [2,686–2,887] |
| S2-company-5000-gateways-1 | 999 [999–999] | 964 [964–964] | 1,284 [1,244–1,292] / 2,397 [2,286–2,633] / 2,845 [2,708–3,315] |
| S2-company-5000-gateways-2 | 999 [999–999] | 965 [964–965] | 128 [123–129] / 224 [212–228] / 986 [944–1,010] |
| S3-slow-0 | 999 [999–999] | 965 [961–966] | 126 [124–126] / 220 [220–222] / 974 [875–1,016] |
| S3-slow-5 | 999 [999–999] | 963 [962–963] | 125 [125–127] / 245 [230–245] / 904 [892–977] |


## Alternatives considered and trade-offs

JFR was used instead of guessing at JSON as the original hotspot: retained allocation samples showed repeated boxed position keys/timestamps in recipient maps. Shared immutable values addressed that first; company-wide serialize-once changed the storage/serialization structure in the matched follow-up. A single laptop gives quick repeatability but shared CPU/RAM; AWS separate hosts can identify a shared API ceiling, at the cost of clock/network variation. Histograms replace retained messages to keep generator memory finite.

## Failure modes and how each is handled

Failed windows are excluded from completed latency comparisons but remain recorded. Missing scrapes are not zero frame rates; reset counters are not negative/zero work. A slow client may never consume its close frame, so server terminations/gauges accompany callbacks. AWS valid windows had 226 gateway scrape timeouts concentrated in CPU-busy cases. Clock snapshots were collected before/after each valid trial on all hosts; largest absolute offset was 0.009832 ms, not proof of continuous synchronization. No ENA allowance counter grew; that excludes observed allowance enforcement, not every network delay.

## Problems encountered and how they were solved

The original Phase 6 profiled 5,000-company-client / ten-minute track / 20-second window exhausted gateway heap. Retained JFR records put ClientSession.position in 313 of 558 gateway execution samples; sampled allocation weight for that method was 3,350,210,312 bytes, including 1,972,567,952 bytes of Long. b5800f1 shares the boxed key and immutable position; the matched rerun method allocation weight is 1,239,833,128 bytes, with no Long sample in that method. Allocation weights are estimates represented by samples, not retained heap, and both end-to-end attempts failed (gateway before, generator after); differing truncated windows do not justify a speedup percentage. [Before profile](../../bench/results/profile/before-snapshots/profile-summary.json), [after profile](../../bench/results/profile/after/profile-summary.json).

59f9508 adds server ingestedAt and bounded generator retention. 13a5f2c shares company frames. On the laptop at 5,000 clients, matched median pipeline p99 improves 1,099→596 ms, live heap 579.4→396.5 MiB and GC pauses 1,877→313 ms; the ranges above show conditions. a536353, e4c40cc, 143687b and 67be39d repair resource-window alignment, clock reporting, reset handling and missing scrape interpretation.

AWS S1 input stays around 41k/s with one/two/four processors while API repeatedly reaches full host CPU. With four processors their mean host CPU is 37–44%; Redis main thread is 25.53% of one core (23.08–29.78%) and Lua 231.04 ms/second (209.08–270.59). The observed shared limit is the API, not a measured processor maximum. One 5,000-client gateway averages 94% host CPU; two average 55–58%, with p99 2,845→986 ms. Two gateways at 10,000 each average about 94% and p99 is 2,809 ms.

The old 1,000-client smoke p99 was 1,142 ms, but gateway mean/peak host CPU was only 23.94%/69.65% in its aligned window. No retained stage profile identifies that tail; sustained two-core saturation cannot be asserted.

## Evidence: tests by name, measured numbers with conditions

Sources: [laptop summary](../../bench/results/Phase61/summary.json), per-trial manifests/reports under bench/results/Phase61/, [AWS summary](../../bench/results/AWS/gate2-summary.json), [resource CSV](../../bench/results/AWS/gate2-resources.csv), [Redis CSV](../../bench/results/AWS/gate2-redis.csv), [member lag CSV](../../bench/results/AWS/gate2-consumer-lag.csv) and [full AWS report](../verification/aws-gate2-matrix.md). Raw logs/histograms/JFR stay local and ignored.

Laptop matched S3: three trials at 1,000 company clients, 5% paused, four-hour subset/global 3,000 positions/s, 110-second windows, tracing off. All 50 slow connections terminate, 950 remain; normal p99 worsens 424 [267–434]→554 [446–649] ms, so unchanged p99 was not shown. AWS S3: three trials/case at 5,000 company clients, two gateways, above AWS rate/window; all 250 slow sessions terminate, zero normal terminations, normal p99 974 [875–1,016]→904 [892–977] ms. p95 rises 220→245 ms; only this workload supports p99 isolation. Real-track naive/hysteresis flaps are 8/3 on the same fresh-state four-hour replay, without labeled ground truth.

## Known limits and what production would need

No continuous steady-state capacity, confidence intervals, failover behavior or labeled geofence accuracy. Trials include startup/JIT, sampled drain windows and coalescing; frames/s is not source positions/s. Pipeline samples measure delivered surviving observations and do not expose replaced points/paused clients. Clocks are sampled snapshots. Laptop and AWS placements/rates differ; do not combine their values into a scale ratio. No new benchmark was run for documentation.

## Likely interview questions with short, precise answers

**Q: Why was emittedAt misleading?**

A: It includes generator batching and quota backoff before API receipt.

**Q: What is primary latency now?**

A: Client callback receipt minus server ingestedAt.

**Q: Does a frame equal a position?**

A: No; frames batch positions and coalescing discards superseded points.

**Q: How is processor rate defined?**

A: Consumed positions divided by replay start to sampled zero-lag drain.

**Q: What did JFR show?**

A: Per-recipient map allocation/boxing, rather than a proven dominant JSON hotspot.

**Q: What fixed company fan-out?**

A: One pending map and serialized frame per tenant, shared across bounded mailboxes.

**Q: Why did processors not scale input?**

A: The shared API stayed CPU-busy while extra processors had headroom.

**Q: Did two gateways help?**

A: For matched AWS 5,000 clients, p99 2,845→986 ms and gateway CPU fell.

**Q: Were slow clients harmless?**

A: AWS p99 isolation held in that workload; laptop p99 worsened.

**Q: Can smoke p99 be blamed on gateway CPU?**

A: Not from retained evidence; its aligned mean CPU was low and no stage profile exists.
