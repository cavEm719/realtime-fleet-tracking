# Phase 5.5 review fixes and Phase 6.1 verification

Verified in the original Desktop project on 2026-10-08 with JDK 21 and OrbStack.


## Functional verification

- Four Phase 5.5 review fixes were committed separately; intermediate full Maven
  verification passed 112 tests.
- Final command: `mvn -B -ntp verify`.
- Result: BUILD SUCCESS, 118 tests (76 unit, 42 integration), no failures/errors/skips.
- The metrics-parser Python regression test passed; benchmark Python modules compile.
- No real LLM request or paid provider was used.

## Measurements

- 35 retained attempts: 33 completed, one baseline gateway heap-exhaustion failure,
  one exploratory Compose startup failure before replay during harness replacement.
- Three completed trials per requested key matched case; one before/after attempt
  each at 10,000 clients. The failed baseline's incomplete latency is excluded.
- All completed latency trials have tracing disabled, zero API/replayer 429s,
  zero 503 retries, and no missing or negative pipeline timestamp samples.
- Median 5,000-client pipeline p99: 1,099 -> 596 ms; live heap after GC:
  579.4 -> 396.5 MiB; GC pauses: 1,877 -> 313 ms.
- Optimized 10,000-client trial completed with p99 1,273 ms; baseline exhausted heap.
- Slow-client trials terminated 50/1,000 connections for overflow, leaving 950 open.
  Normal-client p99 increased from median 424 to 554 ms: no-impact is not established.
- Real-track flaps: naive 8, hysteresis 3. This is a proxy, not labeled accuracy.
- Processor drain throughput, one vs two: 37,376 -> 33,641 positions/s; observer
  pipeline p99: 1,245 -> 945 ms, with overlapping ranges.
- Gateway p99 behind nginx, one vs two: 1,643 -> 845 ms; received frames/s:
  20,522 -> 32,936. These use different placement/heaps from the host comparison.

README records definitions, configurations, medians/min/max, failures and caveats.
Raw manifests, scrapes, histograms, logs, lag and event rows remain under
`bench/results/Phase61/`; the original Phase 6 results were not overwritten.
Bulk raw logs/scrapes/histograms remain locally and are ignored by Git; compact
provenance and summaries are committed. Both benchmark Compose projects are stopped. Original demo volumes remain intact.
