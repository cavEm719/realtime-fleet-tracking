# Gate 1 paid-plan smoke result

A vessel-report replay reached the API on one host, passed through Kafka and the processor on separate hosts, and reached 1,000 company-wide WebSocket subscribers through nginx and the gateway. The nine-host smoke fleet is working. These are short readiness trials, not the full three-trial scale-out matrix or a sustainable-throughput claim. The earlier Free-plan failure and cleanup remain recorded separately.

| Trial | Measured result |
| --- | --- |
| Short S1, one processor | 11,669 acknowledged and processed; replayer 6,504.44 positions/s over 1.7940 s; final lag 0, sampled peak lag 4,175 |
| S1 processing window | 5.1896 s including remote/container start and lag-drain checks; 2,248.51 positions/s over this window |
| S2, one gateway, 1,000 company-wide clients across three tenants | 1,000 connected/subscribed; 0 failed/terminated; 2,055.76 frames/s over 30 s |
| S2 pipeline latency, receipt minus API ingestion time | p50 102 ms / p95 657 ms / p99 1,142 ms; 8,236,669 per-client position observations |
| S2 secondary latency, receipt minus generator emission time | p50 1,314 ms / p95 2,511 ms / p99 3,307 ms |
| S2 replay and quotas | 35,000 positions acknowledged and processed; 996.44 positions/s; zero API rate-rejection delta, zero 429/503 replay retries; final lag 0, sampled peak 464 |

S1's replayer and processor rates use different denominators; the processor window includes remote startup and lag-drain checks. This tiny trial cannot identify a steady-state bottleneck or establish scale-out behavior. S2's p99 exceeds one second and is reported as measured. Position observations repeat across subscribers and are not independent source reports. Each scenario has one successful trial; no three-trial median/range claim is made.

Tracing was disabled. API/copilot/processor/gateway used `-Xms256m -Xmx1g`, 2 GiB Docker limits. The single load generator used `-Xms512m -Xmx3g`, 4 GiB limit and bounded histograms. Hosts ran Amazon Linux 2023 ARM64 on Graviton: four c7g.large application/nginx/processor/gateway hosts, three m7g.large Kafka/Redis/copilot hosts, r7g.large MySQL and c7g.xlarge generator; 20 vCPU total. Raw setup evidence retains actual CPU, RAM, OS, Docker information and clock sources.

S1 used the retained 10-minute NOAA subset (11,669 rows); S2 used the first 35,000 rows from the retained four-hour West Coast subset. Exact checksums are in the compact summary. All nine hosts have before/after chrony samples: maximum absolute reported System-time offset was 0.003161 ms for S1, 0.001665 ms for S2, below the configured 5 ms threshold. There were no clock-observer or sampler errors/overruns in accepted trials. These snapshots do not prove zero clock error between samples.

## Problems found and corrected

- Amazon Linux's bundled AWS CLI was already installed, but requesting nonexistent package awscli2 stopped bootstrap. Removed that package and repaired the existing hosts over SSH. The original cloud-init failure remains historical; future user-data is corrected. Existing Terraform user-data changes must be visible in the next reviewed plan.
- Remote Docker login would store ECR pull tokens. A credential helper now obtains short-lived tokens in memory from the instance role.
- Docker build context excluded the two generator JARs. Included them and pushed both ARM images.
- Broken nested shell quoting stopped the MySQL readiness probe. Corrected quoting and tested that the password is expanded only inside the container.
- A listener ID overrode the configured processor consumer group. The processor consumed under position-listener while the observer watched fleet-processor. Corrected group selection and added a Spring integration check.
- Added the omitted copilot host to clock/resource collection.
- Kafka reset could delete some groups while others expired, then retry already deleted IDs. Refreshes the group list on retry; a regression test reproduces partial deletion.

Each correction is in a separate commit listed in the compact summary. S1 trial 1 failed lag observation; trial 2 failed reset setup; S2 trial 1 was aborted before measurement. These attempts remain in ignored raw evidence and are not smoothed into successful measurements. Accepted results are S1 trial 3 and S2 trial 2.

Final `mvn -B -ntp verify`: **139 tests passed (93 unit, 46 integration), zero failures/errors/skips**, 1 minute 10 seconds. Ten benchmark-tool Python tests passed. Public-safety checks ran before every commit.

## Gate and cleanup state

Apply started 2026-10-09T16:38:01.628399+00:00; all instances launched 2026-10-09T16:38:18+00:00. Report checkpoint 2026-10-09T17:02:04.684716+00:00, 24.05 minutes after apply. EC2/EBS/public IPv4 estimate **$0.3453**, remaining gross allowance **$24.6547**; no credits subtracted, no model calls. This is an estimate, not a billed amount.

Results were copied off all nine hosts. The fleet remains running only for the approved gate wait. Without user reply, cleanup starts at 2026-10-09T18:02:04.684716+00:00; the absolute five-hour deadline is 2026-10-09T21:38:01.628399+00:00. A local watchdog and heartbeat monitor mandatory destruction and remaining-resource audit. Gate 2 has not started. Claude agreement status will be refreshed before Gate 3; no agreement was created during smoke.

Compact evidence: [gate1-paid-summary.json](../../bench/results/AWS/gate1-paid-summary.json). Raw outputs stay in ignored `bench/results/AWS/Gate1-*/` and gate1-private folders. The laptop and earlier failed-attempt baselines are unchanged.
