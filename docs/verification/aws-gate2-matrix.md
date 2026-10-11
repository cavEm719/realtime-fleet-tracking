# AWS Gate 2 benchmark report

Vessels send observations to the API, which waits for Kafka to accept them. Processor workers consume the reports, update Redis and confirmed port state, and gateways deliver the newer values to subscribed clients. This experiment measures that chain on separate AWS hosts. S1 drives the source as fast as it can; S2/S3 keep source rate fixed while changing client count, gateway count or client reading behavior.

This report keeps the original laptop and Gate 1 smoke results as baselines. All values below are actual completed-trial measurements; medians include the full observed min/max range. Raw trials are retained locally under `bench/results/AWS/<scenario>/trial-N/` and excluded from Git because their inventories contain private operator metadata.

## Scale-up and setup

The reviewed scaling plan added processor-2, processor-3, processor-4 (c7g.large), gateway-2 (c7g.large) and loadgen-2 (c7g.xlarge): **5 add / 0 change / 0 destroy**. The initial unrestricted plan also showed nine existing user-data updates from the reviewed Gate 1 bootstrap corrections. Those updates were excluded from the additions-only plan. A subsequent output-only refresh made the inventory contain all 14 hosts without modifying AWS resources.

The root login identity, absence of foreign On-Demand Standard instances and unchanged operator /32 were checked before scaling. All resources remain Terraform-owned, tagged `project=fleet-bench`, and in us-east-1. The 14 hosts use exactly 32 vCPU. The original apply clock and five-hour deadline were not reset.

| Host | Instance | vCPU | Reported RAM bytes |
| --- | --- | ---: | ---: |
| api | c7g.large | 2 | 3,990,900,736 |
| copilot | m7g.large | 2 | 8,139,177,984 |
| gateway-1 | c7g.large | 2 | 3,990,900,736 |
| gateway-2 | c7g.large | 2 | 3,990,900,736 |
| kafka | m7g.large | 2 | 8,139,177,984 |
| loadgen-1 | c7g.xlarge | 4 | 8,138,899,456 |
| loadgen-2 | c7g.xlarge | 4 | 8,138,899,456 |
| mysql | r7g.large | 2 | 16,433,635,328 |
| nginx | c7g.large | 2 | 3,990,900,736 |
| processor-1 | c7g.large | 2 | 3,990,900,736 |
| processor-2 | c7g.large | 2 | 3,990,900,736 |
| processor-3 | c7g.large | 2 | 3,990,900,736 |
| processor-4 | c7g.large | 2 | 3,990,900,736 |
| redis | m7g.large | 2 | 8,139,177,984 |

All hosts reported aarch64, Amazon Linux 2023, kernel 6.18.51-120.163.amzn2023.aarch64. API/copilot/processor/gateway JVM: `-Xms256m -Xmx1g`, Docker 2 GiB. Each generator: `-Xms512m -Xmx3g`, Docker 4 GiB. WebSocket clients are split equally over two generator processes, which retain bounded histograms and parse timestamps instead of retaining delivered-message objects. All trials ran with tracing disabled. Image tag: `smoke-reset-fix-1`.

S1 replays the complete NOAA 2023-01-01 West Coast 00:00–04:00 UTC subset: 261,255 rows and 3,249 MMSIs. The latency cases use its first 125,000 rows, a global 1,000 positions/s offer, a 120-second client window and five seconds of source padding. Clients rotate over three tenants and all subscribe company-wide. Tenant quotas are 250,000 positions/s each. Twelve MMSI-hashed sequential sending lanes preserve vessel order.

Full source SHA-256: `434ab651deea8d11eff98cef45a0bc3c3bf7d91704b3906d269deaeeb30472d6`. Latency prefix SHA-256: `68773f9dd92dafbc260c1321e330a1651c6d879aebef4dc5c3f477110816147e`; the latter was checked against the actual generator file. The exact NOAA download and subset commands remain in README's Phase 6 setup. The AWS harness invocation uses `--dataset data/west-coast-4h.csv --seconds 120 --latency-rate 1000 --quota 250000 --reset-demo` with the existing inventory/key/tag and the selected scenario family.

Before each trial, application containers are restarted and experiment rows/Redis data/Kafka topics are reset. These are short, cold application replays, not long steady-state capacity tests. Configurations run sequentially on the same hosts rather than in randomized order.

## S1: full four-hour max-rate replay

| Processors | Generator ingest positions/s | Full pipeline positions/s | Sampled peak lag |
| ---: | ---: | ---: | ---: |
| 1 | 40,899.4 [39,849.9–41,041.8] | 29,689.7 [25,964.2–30,337.4] | 18,692 [10,057–41,879] |
| 2 | 40,878.7 [40,225.3–41,521.0] | 26,034.4 [25,776.2–30,927.7] | 19,597 [14,499–21,539] |
| 4 | 41,303.9 [40,880.3–41,699.9] | 30,425.3 [26,330.1–30,720.6] | 11,160 [3,868–17,862] |

Generator throughput divides acknowledgements by the replayer's internal elapsed time. Full-pipeline throughput divides accepted plus Redis-filtered processor counters by the independently measured start-to-lag-zero window, which includes remote generator startup and drain observation. These denominators differ. All completed S1 trials acknowledged the full 261,255 rows and ended with lag zero; source duplicates/older reports are accounted for by the processor's filtered counter.

## S2/S3: company-wide clients

| Scenario | Trials | Pipeline p50 ms | p95 ms | p99 ms | Client frames/s | Gateway frames/s (sampled) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| S2-company-10000-gateways-2 | 3 | 1,286 [1,201–1,348] | 2,404 [2,279–2,462] | 2,809 [2,686–2,887] | 11,329.6 [11,033.2–12,145.0] | 11,341.3 [11,109.1–12,236.7] |
| S2-company-5000-gateways-1 | 3 | 1,284 [1,244–1,292] | 2,397 [2,286–2,633] | 2,845 [2,708–3,315] | 5,992.6 [5,470.8–6,037.6] | 6,018.7 [5,518.8–6,051.2] |
| S2-company-5000-gateways-2 | 3 | 128 [123–129] | 224 [212–228] | 986 [944–1,010] | 9,203.4 [9,139.7–9,325.6] | 9,190.1 [9,107.8–9,304.0] |
| S3-slow-0 | 3 | 126 [124–126] | 220 [220–222] | 974 [875–1,016] | 9,267.6 [9,256.7–9,314.9] | 9,281.9 [9,220.7–9,286.4] |
| S3-slow-5 | 3 | 125 [125–127] | 245 [230–245] | 904 [892–977] | 8,764.9 [8,762.3–8,797.6] | 8,851.4 [8,846.0–8,922.5] |

Pipeline latency is receipt time minus API `ingestedAt`. The secondary generator `emittedAt` latency remains in the compact JSON; the processor latency timer uses that secondary clock and is not used to explain pipeline p99. Histograms are merged by counts across the two generators within a trial; percentiles are then summarized across trials. A position received by many clients contributes many observations, not independent source samples. Frames/s counts batched frames, not source positions/s. Gateway frame rates use counter differences between sampled timestamps inside the measurement window, with each counter's own elapsed denominator.

| Scenario | Connected | Setup failures | Normal terminations observed | Slow terminations observed | Gateway overflow terminations |
| --- | ---: | ---: | ---: | ---: | ---: |
| S2-company-10000-gateways-2 | 10,000 [10,000–10,000] | 0 [0–0] | 0 [0–0] | 0 [0–0] | 0 [0–0] |
| S2-company-5000-gateways-1 | 5,000 [5,000–5,000] | 0 [0–0] | 0 [0–0] | 0 [0–0] | 0 [0–0] |
| S2-company-5000-gateways-2 | 5,000 [5,000–5,000] | 0 [0–0] | 0 [0–0] | 0 [0–0] | 0 [0–0] |
| S3-slow-0 | 5,000 [5,000–5,000] | 0 [0–0] | 0 [0–0] | 0 [0–0] | 0 [0–0] |
| S3-slow-5 | 5,000 [5,000–5,000] | 0 [0–0] | 0 [0–0] | 0 [0–0] | 250 [250–250] |

Slow clients stop requesting frames after subscription. A client that requests no further callbacks may not observe the remote close; server overflow counters and observed client closes are therefore reported separately. Intentional test shutdown is excluded from client termination counts.

## Failed 10,000-client single-gateway preparation

Two attempts failed before a valid replay/latency window; the third was skipped under the two-failure rule. The second attempt connected **8,192 of 10,000 clients**, with **1,808 setup failures** (4,137/863 and 4,055/945 on the two generators). The packaged Spring Boot 3.5.16 metadata gives `server.tomcat.max-connections` a default of 8,192, and this deployment does not override it. The exact connection count and configuration support a connection-cap explanation, not a claim that the host cannot serve 10,000 clients.

Preparation gateway mean CPU was 12.83% and 12.47%, with brief peaks near 100%. The two preparations also had 57 and 54 gateway metrics timeouts, consistent with new HTTP requests being unable to obtain a connection. All-host preparation CPU/container/network/Redis/member-lag samples are retained separately in the JSON. These are not mixed into valid workload medians. The first attempt's original harness exception was overwritten during collection; its incomplete status and missing histogram remain. The second primary error was captured separately. The harness now preserves primary errors and readiness logs, with regression tests. Failed attempts lack after-run clock snapshots; no latency values are reported for them.

## Resource evidence by scenario

Host CPU is normalized over all vCPU; Docker 100% means one core. Each scenario below reports median per-trial mean CPU and its range, plus the largest sampled peak. The compact CSV also contains every inactive host, every observed container, network peaks, allowance-counter deltas, sampler errors and maximum observed clock offset. Redis Lua is aggregate EVAL/EVALSHA time for ingest token buckets and position batches, not isolated script profiling.

### S1-processors-1

| Host | Mean CPU % | Peak CPU % | Main container mean CPU % of one core | RX / TX peak MiB/s |
| --- | ---: | ---: | ---: | ---: |
| api | 65.4 [56.7–70.5] | 100.0 | 118.4 [110.9–120.7] | 9.0 / 4.4 |
| copilot | 1.4 [1.3–1.8] | 3.0 | 0.1 [0.1–0.1] | 0.0 / 0.0 |
| gateway-1 | 4.6 [4.3–4.8] | 10.3 | 5.6 [5.6–6.2] | 0.0 / 0.0 |
| kafka | 14.1 [11.0–29.2] | 58.1 | 25.7 [17.1–48.4] | 4.5 / 4.8 |
| loadgen-1 | 19.5 [17.4–19.8] | 45.0 | not measured | 0.1 / 9.5 |
| loadgen-2 | 0.6 [0.6–0.6] | 1.0 | not measured | 0.0 / 0.0 |
| mysql | 8.8 [8.8–9.2] | 18.7 | 7.4 [6.9–7.7] | 0.4 / 0.2 |
| nginx | 1.3 [1.3–1.6] | 3.0 | 0.0 [0.0–0.0] | 0.0 / 0.0 |
| processor-1 | 54.2 [48.2–62.2] | 98.5 | 94.8 [93.9–107.6] | 4.9 / 26.0 |
| redis | 15.3 [14.7–15.9] | 34.2 | 20.9 [20.5–21.4] | 25.4 / 0.4 |

Redis main-thread CPU % of one core: 24.65 [21.48–25.26]; Lua ms/second: 225.34 [196.01–230.93]; Lua µs/call: 788.3 [764.1–794.0].

The API reached 100% host CPU repeatedly (14 samples at least 90% across the three trials); processor-1 also had seven such samples. Redis main-thread use stayed around a quarter of one core, and MySQL/Kafka had headroom. This short replay shows transient API and processor CPU pressure, not a sustained Redis or network capacity limit.

### S1-processors-2

| Host | Mean CPU % | Peak CPU % | Main container mean CPU % of one core | RX / TX peak MiB/s |
| --- | ---: | ---: | ---: | ---: |
| api | 59.3 [58.5–68.4] | 100.0 | 113.7 [109.3–117.0] | 9.0 / 4.4 |
| copilot | 1.4 [1.4–1.5] | 2.0 | 0.1 [0.1–0.1] | 0.0 / 0.0 |
| gateway-1 | 4.5 [4.4–4.8] | 10.5 | 5.9 [5.4–5.9] | 0.0 / 0.0 |
| kafka | 10.2 [9.7–15.2] | 31.3 | 17.8 [14.7–24.8] | 4.4 / 4.4 |
| loadgen-1 | 17.4 [17.3–20.5] | 46.0 | not measured | 0.1 / 8.8 |
| loadgen-2 | 0.6 [0.6–0.7] | 1.0 | not measured | 0.0 / 0.0 |
| mysql | 8.4 [8.4–9.2] | 15.7 | 7.3 [7.2–8.3] | 0.4 / 0.2 |
| nginx | 1.4 [1.4–1.5] | 2.5 | 0.0 [0.0–0.0] | 0.0 / 0.0 |
| processor-1 | 46.8 [40.1–53.7] | 93.0 | 90.6 [78.6–91.3] | 2.4 / 11.4 |
| processor-2 | 44.2 [40.7–50.1] | 93.5 | 83.2 [78.8–85.9] | 2.4 / 10.6 |
| redis | 15.4 [14.4–15.6] | 30.9 | 21.4 [20.6–21.7] | 19.8 / 0.3 |

Redis main-thread CPU % of one core: 22.90 [22.45–25.58]; Lua ms/second: 207.45 [204.41–232.00]; Lua µs/call: 531.5 [484.7–541.3].

The API again had 14 samples at least 90% host CPU, whereas each processor had only one. Generator throughput remained about 40.9k positions/s. The lower observed pipeline median is retained: cold startup, short drain windows and trial variation prevent interpreting it as a steady-state regression caused by a second processor.

### S1-processors-4

| Host | Mean CPU % | Peak CPU % | Main container mean CPU % of one core | RX / TX peak MiB/s |
| --- | ---: | ---: | ---: | ---: |
| api | 66.8 [57.4–68.5] | 100.0 | 114.8 [110.8–117.5] | 9.1 / 4.4 |
| copilot | 1.5 [1.4–1.7] | 3.0 | 0.1 [0.1–0.1] | 0.0 / 0.0 |
| gateway-1 | 5.1 [4.9–5.2] | 10.1 | 6.2 [5.8–6.8] | 0.0 / 0.0 |
| kafka | 13.4 [11.0–15.3] | 26.8 | 20.6 [19.8–21.2] | 4.6 / 4.5 |
| loadgen-1 | 20.8 [16.6–20.9] | 41.1 | not measured | 0.1 / 8.9 |
| loadgen-2 | 0.8 [0.6–0.9] | 2.5 | not measured | 0.0 / 0.0 |
| mysql | 9.5 [8.7–10.8] | 22.0 | 8.4 [8.1–9.0] | 0.5 / 0.3 |
| nginx | 1.3 [1.3–1.9] | 6.9 | 0.0 [0.0–0.0] | 0.0 / 0.0 |
| processor-1 | 40.8 [36.5–44.4] | 77.6 | 72.9 [69.8–76.6] | 1.3 / 6.2 |
| processor-2 | 37.4 [34.6–44.9] | 90.5 | 69.1 [63.9–76.5] | 1.3 / 7.1 |
| processor-3 | 42.8 [38.2–44.1] | 85.5 | 75.3 [73.8–78.7] | 1.1 / 4.6 |
| processor-4 | 44.0 [37.7–45.6] | 91.4 | 78.2 [73.2–79.9] | 1.2 / 5.3 |
| redis | 16.1 [15.2–17.7] | 31.7 | 22.0 [21.4–23.7] | 20.5 / 0.4 |

Redis main-thread CPU % of one core: 25.53 [23.08–29.78]; Lua ms/second: 231.04 [209.08–270.59]; Lua µs/call: 312.1 [301.3–342.5].

API CPU still reached 100% (13 samples at least 90%); processor mean CPU fell to 37–44%. Input throughput stayed about 41k positions/s despite four processors. The evidence points to the upstream API as the observed shared constraint; it does not establish a precise maximum processor capacity. Peak lag decreased, but full-pipeline throughput ranges overlap. No host showed growth in ENA allowance-exceeded counters.

### S2-company-10000-gateways-2

| Host | Mean CPU % | Peak CPU % | Main container mean CPU % of one core | RX / TX peak MiB/s |
| --- | ---: | ---: | ---: | ---: |
| api | 6.8 [5.8–7.0] | 62.8 | 10.0 [9.9–10.1] | 0.2 / 0.2 |
| copilot | 1.5 [1.5–1.5] | 7.4 | 0.1 [0.1–0.1] | 0.0 / 0.0 |
| gateway-1 | 94.1 [93.4–94.6] | 100.0 | 183.4 [181.9–184.2] | 2.7 / 336.3 |
| gateway-2 | 93.8 [92.6–94.1] | 100.0 | 182.1 [180.6–182.7] | 2.8 / 335.2 |
| kafka | 2.1 [2.1–2.4] | 8.3 | 1.9 [1.9–2.0] | 0.1 / 0.2 |
| loadgen-1 | 66.6 [66.2–68.6] | 94.5 | 250.7 [250.4–259.7] | 326.1 / 3.4 |
| loadgen-2 | 65.6 [65.3–67.1] | 87.1 | 254.9 [253.4–260.9] | 329.8 / 3.3 |
| mysql | 5.8 [5.8–5.8] | 11.4 | 2.5 [2.4–2.5] | 0.1 / 0.0 |
| nginx | 55.6 [55.1–56.4] | 67.1 | 72.5 [72.3–73.5] | 651.0 / 651.2 |
| processor-1 | 8.4 [7.1–8.4] | 61.7 | 12.2 [11.8–12.2] | 0.2 / 0.7 |
| redis | 5.0 [5.0–5.0] | 11.3 | 2.3 [2.3–2.4] | 0.6 / 1.0 |

Redis main-thread CPU % of one core: 1.25 [1.24–1.26]; Lua ms/second: 8.35 [8.29–8.38]; Lua µs/call: 423.9 [420.7–424.0].

Both gateways were persistently CPU-busy: about 94% host mean CPU and about 182–183% container CPU, meaning nearly both cores. The proxy and four-core generators had lower mean CPU; Redis main-thread use was about 1.25% of one core. Gateway CPU pressure is the measured constraint. Network allowance counters did not grow, although that cannot exclude every network delay.

### S2-company-5000-gateways-1

| Host | Mean CPU % | Peak CPU % | Main container mean CPU % of one core | RX / TX peak MiB/s |
| --- | ---: | ---: | ---: | ---: |
| api | 6.0 [5.9–7.9] | 62.4 | 10.4 [9.9–10.6] | 0.2 / 0.2 |
| copilot | 1.6 [1.5–1.8] | 6.5 | 0.1 [0.1–0.1] | 0.0 / 0.0 |
| gateway-1 | 94.0 [93.8–94.1] | 100.0 | 183.7 [182.5–183.9] | 2.8 / 334.3 |
| kafka | 2.5 [2.5–3.3] | 9.0 | 2.2 [2.0–2.3] | 0.1 / 0.1 |
| loadgen-1 | 36.5 [36.3–37.3] | 62.4 | 134.6 [131.0–135.9] | 166.3 / 2.0 |
| loadgen-2 | 35.1 [34.4–35.3] | 57.0 | 134.1 [130.9–135.7] | 166.0 / 1.8 |
| mysql | 5.9 [5.8–6.0] | 11.3 | 2.5 [2.4–2.5] | 0.1 / 0.0 |
| nginx | 31.0 [31.0–31.1] | 41.1 | 35.5 [34.7–35.5] | 345.0 / 345.3 |
| processor-1 | 6.9 [6.9–8.2] | 54.2 | 12.1 [12.0–12.2] | 0.2 / 0.6 |
| redis | 5.1 [5.1–5.9] | 10.5 | 2.3 [2.3–2.3] | 0.6 / 0.5 |

Redis main-thread CPU % of one core: 1.23 [1.22–1.24]; Lua ms/second: 8.27 [8.24–8.38]; Lua µs/call: 420.6 [412.5–421.4].

Gateway mean CPU was 94% of its two-core host and container mean CPU about 184% of one core. API, processor, Redis, proxy and generators had headroom. The gateway is the observed CPU constraint, with pipeline p99 median 2,845 ms. This identifies the busy component, not an unmeasured method-level hotspot.

### S2-company-5000-gateways-2

| Host | Mean CPU % | Peak CPU % | Main container mean CPU % of one core | RX / TX peak MiB/s |
| --- | ---: | ---: | ---: | ---: |
| api | 5.8 [5.6–7.4] | 66.5 | 10.4 [10.2–10.5] | 0.2 / 0.2 |
| copilot | 1.5 [1.5–1.5] | 7.4 | 0.1 [0.1–0.1] | 0.0 / 0.0 |
| gateway-1 | 55.1 [54.8–58.5] | 99.5 | 107.0 [106.0–113.9] | 1.8 / 193.9 |
| gateway-2 | 57.9 [55.1–61.1] | 99.0 | 112.7 [106.2–118.8] | 1.8 / 197.0 |
| kafka | 2.4 [2.2–2.4] | 10.1 | 2.2 [2.1–2.4] | 0.1 / 0.1 |
| loadgen-1 | 38.7 [38.1–39.4] | 73.9 | 143.0 [142.2–143.6] | 196.8 / 2.2 |
| loadgen-2 | 37.5 [36.0–38.7] | 73.9 | 143.5 [136.6–148.4] | 201.7 / 2.1 |
| mysql | 6.1 [5.7–6.4] | 11.5 | 2.5 [2.5–2.6] | 0.1 / 0.0 |
| nginx | 32.9 [32.0–33.0] | 46.3 | 40.3 [40.1–40.6] | 405.5 / 406.5 |
| processor-1 | 7.7 [6.8–9.0] | 44.2 | 12.4 [11.9–13.3] | 0.1 / 0.6 |
| redis | 5.0 [4.8–5.2] | 10.1 | 2.4 [2.3–2.4] | 0.5 / 0.8 |

Redis main-thread CPU % of one core: 1.27 [1.25–1.27]; Lua ms/second: 8.50 [8.49–8.53]; Lua µs/call: 428.6 [428.1–430.5].

Splitting the same 5,000-client workload reduces gateway mean CPU to 55–58% and p99 to 986 ms, compared with 2,845 ms on one gateway. No component showed sustained full-host CPU saturation. Brief peaks remain, so this does not promise low tails at higher client counts.

### S3-slow-0

| Host | Mean CPU % | Peak CPU % | Main container mean CPU % of one core | RX / TX peak MiB/s |
| --- | ---: | ---: | ---: | ---: |
| api | 5.7 [5.5–6.3] | 59.3 | 9.9 [9.9–10.2] | 0.2 / 0.2 |
| copilot | 1.7 [1.6–1.9] | 7.0 | 0.1 [0.1–0.1] | 0.0 / 0.0 |
| gateway-1 | 55.5 [53.6–56.8] | 99.5 | 107.5 [104.4–110.3] | 1.9 / 205.9 |
| gateway-2 | 56.9 [56.7–57.5] | 99.0 | 109.5 [108.7–110.8] | 1.9 / 201.9 |
| kafka | 2.3 [2.2–2.6] | 9.5 | 2.0 [2.0–2.2] | 0.1 / 0.2 |
| loadgen-1 | 38.0 [37.8–38.7] | 77.3 | 140.0 [139.2–143.3] | 202.7 / 2.4 |
| loadgen-2 | 36.4 [36.3–37.3] | 71.3 | 140.0 [138.1–142.2] | 200.0 / 2.3 |
| mysql | 5.9 [5.8–6.1] | 11.0 | 2.5 [2.5–2.5] | 0.1 / 0.0 |
| nginx | 32.6 [32.3–32.6] | 47.2 | 40.4 [40.3–40.4] | 402.2 / 402.5 |
| processor-1 | 7.1 [7.1–7.3] | 51.0 | 12.4 [12.3–12.4] | 0.2 / 0.6 |
| redis | 5.1 [4.9–5.2] | 10.1 | 2.3 [2.3–2.4] | 0.5 / 0.8 |

Redis main-thread CPU % of one core: 1.25 [1.25–1.26]; Lua ms/second: 8.37 [8.31–8.45]; Lua µs/call: 422.2 [420.3–428.6].

The matched zero-slow baseline has gateway mean CPU around 55–57%, Redis main-thread use 1.25% of a core, no overflow termination and no sustained full-host CPU saturation. It supplies the comparison for the next configuration.

### S3-slow-5

| Host | Mean CPU % | Peak CPU % | Main container mean CPU % of one core | RX / TX peak MiB/s |
| --- | ---: | ---: | ---: | ---: |
| api | 6.0 [5.6–6.0] | 55.0 | 10.0 [9.8–10.2] | 0.2 / 0.2 |
| copilot | 1.6 [1.5–1.6] | 7.0 | 0.1 [0.1–0.1] | 0.0 / 0.0 |
| gateway-1 | 53.9 [53.7–55.1] | 99.5 | 104.2 [103.9–106.1] | 1.9 / 196.3 |
| gateway-2 | 54.8 [53.8–57.2] | 99.5 | 105.8 [103.9–109.9] | 1.9 / 207.7 |
| kafka | 2.5 [2.3–2.6] | 8.4 | 2.1 [2.0–2.1] | 0.2 / 0.2 |
| loadgen-1 | 36.7 [36.3–38.1] | 73.1 | 136.4 [132.1–141.4] | 186.6 / 2.2 |
| loadgen-2 | 36.0 [34.9–37.0] | 75.9 | 137.8 [131.6–139.1] | 189.0 / 2.1 |
| mysql | 5.9 [5.8–6.1] | 11.1 | 2.5 [2.5–2.6] | 0.1 / 0.0 |
| nginx | 32.0 [32.0–32.1] | 49.0 | 39.7 [39.7–39.9] | 358.8 / 358.4 |
| processor-1 | 6.8 [6.8–7.4] | 46.7 | 12.3 [12.0–12.9] | 0.2 / 0.7 |
| redis | 4.9 [4.8–5.1] | 10.0 | 2.3 [2.3–2.3] | 0.5 / 0.7 |

Redis main-thread CPU % of one core: 1.25 [1.24–1.26]; Lua ms/second: 8.35 [8.26–8.51]; Lua µs/call: 422.5 [419.2–426.1].

All 250 paused clients were terminated by gateway overflow in every trial, while normal clients had zero observed termination. Overflow appeared during the measurement window, before planned shutdown. Normal-client p99 median was 904 ms versus 974 ms without slow clients, within the baseline range; p95 rose modestly from 220 to 245 ms. Therefore this workload supports isolation of the p99 tail, not a claim that every latency statistic was unchanged. Gateway mean CPU was 54–55%; no sustained host CPU saturation was observed.

AWS allowance counters count packets queued/dropped when instance bandwidth, packet-rate or connection limits are exceeded; zero growth rules out observed allowance enforcement, not all possible network delay. [AWS ENA metric definitions](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/monitoring-network-performance-ena.html). Kafka lag was collected per member and per partition. Each processor has three consumer workers; the public consumer-lag CSV maps those members to host names without publishing private addresses.

## Why the 1,000-client smoke p99 was 1,142 ms

The retained 30-second smoke window shows gateway host CPU **23.94% mean / 69.65% peak**, API **10.85% / 39.71%**, processor **13.39% / 46.50%**, Redis **3.86% / 6.40%** and generator **18.52% / 54.80%**. Redis main-thread CPU was 1.20% of a core and Lua used 7.93 ms/second. Gateway peak egress was 92.29 MiB/s; nginx peak TX was 102.80 MiB/s. An after-smoke query at 17:45:46 UTC returned zero for all five gateway allowance-exceeded counters.

That evidence does **not** establish sustained saturation of both gateway vCPU in the smoke trial. The legacy untimestamped Docker stream contains a 190.2%-of-one-core peak over the whole collection, but it cannot be placed inside the client window and may include startup. API request max was 613 ms (mean 29.72 ms), and sampled Kafka lag peaked at 464 records. Short upstream bursts, startup and gateway queue/flush delays could contribute, but independently observed maxima cannot be added to attribute one p99 observation. Aggregate CPU also cannot rule out a temporarily busy single thread. No per-record stage trace/profile was retained, so the exact cause remains unresolved.

The controlled 5,000-client comparison supplies a separate, stronger conclusion: one gateway stays near full CPU and has multi-second latency; splitting the same workload across two gateways reduces CPU pressure and latency. This is evidence about the larger workload, not proof of the old smoke's tail cause.

## Clocks, verification and gate state

The largest absolute before/after chrony System time offset across valid trials was **0.009832 ms**, below the configured 5 ms bound. All 14 hosts were checked before and after every valid trial. These snapshots do not prove continuous clock accuracy. The old smoke window is reconstructed with one-second file-timestamp precision, so its alignment has approximately one-second uncertainty.

There were **226 gateway Prometheus scrape timeouts** in valid windows: 71 in the 5,000-client single-gateway case and 155 in the 10,000-client two-gateway case. CPU-busy endpoints therefore have missing samples; gateway counter rates use the observed timestamp interval and missing/reset counters remain unknown, rather than zero. Host CPU/network and Docker collection were separate: no Docker collection errors, network-counter errors or sampling cycles longer than the one-second target were recorded. No ENA allowance counter increased. Per-member Kafka lag includes fetched-but-uncommitted records as well as waiting records; all valid trials drained to zero. All valid latency trials had zero API 429s and zero source retries from 429 or 503.

`mvn -B -ntp verify` passed **139 tests**, with zero failures, errors or skips, in 01:10. The offline benchmark tests passed **23 tests**. Measurement-tool fixes were committed separately. All 14 host result archives were copied locally and their archive contents/checksums verified before pausing. Public CSV rows include inactive hosts and every measured container; columns distinguish all-vCPU host CPU from one-core Docker CPU, RX/TX peaks and per-trial member assignments. Raw inventories/logs remain ignored.

At 2026-10-09T19:17:28.731084+00:00, estimated gross EC2/EBS/public IPv4 spend is **$3.2122**, leaving **$21.7878** of the $25 limit. Full-fleet runbook rate is $1.353270/hour, calculated from actual launch times of the original nine hosts and five additions; this is not an invoice total and does not subtract credits. Gate 2 is paused for authorization. The fleet may wait until **2026-10-09T20:17:28.731084+00:00** under the approved 60-minute rule, after which the active watchdog copies results again, destroys only this Terraform fleet and runs the remaining-resources audit. The unchanged five-hour hard deadline is **2026-10-09T21:38:01.628399+00:00**. No cleanup is claimed yet because the fleet is in the explicitly authorized gate wait.

Compact evidence: [performance summary](../../bench/results/AWS/gate2-summary.json), [host/container resource summary](../../bench/results/AWS/gate2-resources.csv), [consumer lag per member](../../bench/results/AWS/gate2-consumer-lag.csv), [Redis CPU/Lua summary](../../bench/results/AWS/gate2-redis.csv). Raw outputs and full before/after chrony information remain local and ignored. Bedrock has not been invoked during Gate 2; model access/calibration/pilots remain behind Gate 3 approval.

## Subsequent operator-requested cleanup

Gate 2 was approved and the operator requested immediate destruction. All 14 result archives were copied again and verified; Terraform destroyed 30 resources, with an empty state list. The audit reports 14 terminated instances, no tagged volumes/snapshots and no fleet role/profile candidates. Tagging API still returned deleted instance/volume records immediately after cleanup. [Cleanup summary](../../bench/results/AWS/gate2-cleanup-summary.json) records this distinction and the conservative final cost estimate. Gate 3 remains deferred to a separate smaller deployment; the cleanup automation is paused.
