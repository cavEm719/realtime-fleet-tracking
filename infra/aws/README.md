# AWS fleet benchmark: operator runbook

A shipping company sends vessel reports to the API. Kafka forwards them to processors; Redis supplies the latest positions to WebSocket gateways, while confirmed port crossings are stored in MySQL and relayed through Kafka. This benchmark puts the busy components on separate machines so measurements can distinguish processor work from contention on the laptop. The copilot asks the API for company-scoped facts and calls Bedrock using its host's instance role. No shared database access is added to copilot or gateway.

**Paid-plan Gate 1 smoke passed; full matrix not run.** All nine hosts started; short S1 and 1,000-client S2 completed after deployment/tooling corrections. [Smoke evidence](../../docs/verification/aws-gate1-smoke.md) includes failures, fixes, measured latency and the cost/deadline checkpoint. The earlier Free-plan attempt and mandatory cleanup remain [historical evidence](../../docs/verification/aws-gate1.md). No real model was invoked. All cloud commands require explicit operator authorization; this execution delegates apply/destroy only under the agreed gates and limits. Account changes remain user-only.

## 1. Inspect configuration and cost before provisioning

Prerequisites on your workstation: JDK 21, Maven, Docker with buildx, Terraform >=1.6, AWS CLI, Python 3, SSH and an existing AWS login. Do not put credentials in Terraform variables or this repository. IAM provisioning/pushing permissions belong to your operator identity; EC2 roles have only ECR authentication/pull and `bedrock:InvokeModel` on the explicitly selected ARNs. No Bedrock permission is granted by default. Converse uses InvokeModel permission; streaming is not enabled.

Defaults in `variables.tf` provision fourteen ARM hosts in one AZ: Kafka, Redis, MySQL, API, copilot and nginx, four processors, two gateways, and two load generators. Application instances use dedicated host networking and 2 GiB container limits / `-Xms256m -Xmx1g`; generators use 4 GiB / `-Xms512m -Xmx3g`. Kafka uses a 1 GiB heap. All supported taggable resources carry `project=fleet-bench`; associations and inline policies have no independent tag API. This is a disposable single-broker, single-database, single-AZ benchmark without HA or internal authentication/TLS.

The security group allows TCP 22, 8080 (API) and 8082 (nginx/gateway) only from `my_ip_cidr`, which must be your IPv4 /32. TCP service ports, including Kafka 9092, Redis 6379, MySQL 3306, gRPC 9095 and copilot 8083, are accessible within `10.42.0.0/16`, not from the internet. Egress permits package/image downloads and Bedrock HTTPS. Every host has a public IPv4 for operator SSH; services advertise private IPs. IMDSv2 is required, with hop limit 2 for container role access. Graviton ARM AMIs come from the Amazon Linux 2023 SSM parameter. Chrony uses Amazon Time Sync at `169.254.169.123` ([AWS instructions](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configure-ec2-ntp.html)).

US East (N. Virginia), Linux On-Demand estimate checked against the [public AWS EC2 price feed](https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ec2-ondemand-without-sec-sel/US%20East%20%28N.%20Virginia%29/Linux/index.json) on 2026-10-08:

| Hosts | Size | Count | USD/hour each | Subtotal |
| --- | --- | ---: | ---: | ---: |
| Kafka, Redis, copilot | m7g.large | 3 | 0.0816 | 0.2448 |
| MySQL | r7g.large | 1 | 0.1071 | 0.1071 |
| API, nginx, processors, gateways | c7g.large | 8 | 0.0725 | 0.5800 |
| Generators | c7g.xlarge | 2 | 0.1450 | 0.2900 |
| Compute total | | 14 | | **1.2219** |

At 40 GiB gp3 per host and fourteen public IPv4s, the approximate combined baseline is **$1.3533/hour** (730-hour month approximation). This is an estimate, not measured spend; it excludes Bedrock, ECR storage/scanning, internet transfer and taxes. [gp3 pricing](https://aws.amazon.com/ebs/pricing/) and [public IPv4 pricing](https://aws.amazon.com/vpc/pricing/) add roughly $0.0614/hour and $0.0700/hour respectively. Refresh before running:

```sh
python3 infra/aws/scripts/cost.py
```

For this execution, the user superseded the Budget instruction: **do not create AWS Budgets or email alerts**. Enforce the internal $25 whole-phase estimate in `bench/results/AWS/cost-log.md`, the five-hour deadline from apply, and mandatory cleanup. Budget/email creation would require new explicit user instructions.

## 2. Provision: operator only

Copy `infra/aws/terraform.tfvars.example` to `infra/aws/terraform.tfvars`. Set your real `my_ip_cidr`, public SSH key, region/AZ, instance sizes and desired counts. Four processors/two gateways are needed for the full matrix. Select a tool-capable Converse model and judge supported in your region, confirm account access, and add their exact model ARN(s) to `bedrock_model_arns`. If using a cross-region inference profile, include that profile and every authorized destination model ARN; regional policies and model access still apply. Review current [Bedrock model support](https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference-supported-models-features.html) and [Spring AI Converse support](https://docs.spring.io/spring-ai/reference/api/chat/bedrock-converse.html). The implementation uses the project-pinned Spring AI 1.1.8, not the current 2.x examples.

```sh
terraform -chdir=infra/aws init
terraform -chdir=infra/aws plan -out=plan
terraform -chdir=infra/aws apply plan
terraform -chdir=infra/aws output -json inventory > infra/aws/inventory.json
```

Apply/destroy require explicit operator authorization; the current execution delegates them under the agreed gates, spend/time limits and mandatory cleanup. State, tfvars and inventory are ignored by git. Keep the Terraform state until cleanup; it owns the resource identities. User-data installs Docker, chrony and collection tools, not application containers; wait for `cloud-init status --wait` (the deployment script does this). ARM AMI/instance availability and Docker image platform support still need live AWS verification.

### Evaluation-only profile

For a separate copilot evaluation, set `fleet_profile = "evaluation"`. The profile provisions only Kafka, Redis, MySQL, API, copilot and one runner (`loadgen-1`). It omits processors, gateways and nginx regardless of benchmark count settings, creates only API/copilot image repositories, and omits external gateway port 8082. The default `benchmark` profile and its scaling counts remain unchanged. The runner performs judge calls through its instance role; the workstation keeps the private SSH key and all task outputs.

The evaluation authorization uses a separate $12 gross estimated-spend ceiling and three-hour deadline from apply. Access verification token charges count toward that allowance. Before apply, verify exact model access, operator /32, empty prior fleet state and an additions-only plan. Deploy and seed with the fixed fixture clock; retain every result and destroy/audit at the end, including calibration failure. Marketplace `PENDING` is acceptable only after the operator-authorized 30-minute polling window and successful fresh minimal checks immediately before apply.

## 3. Build and push immutable ARM images

From the project root, choose a unique tag for each build (the ECR repositories reject overwriting tags):

```sh
infra/aws/scripts/push-images.sh infra/aws/inventory.json phase7-$(git rev-parse --short HEAD)-1
```

The script runs Maven verification and uses buildx `--platform linux/arm64` for API, copilot, processor, gateway, replayer and loadtest, then pushes to Terraform-created ECR repositories. It never creates repositories. Public infra images are version-pinned (Kafka 3.9.1, Redis 7.4.9, MySQL 8.0.46, nginx 1.27.5); verify ARM manifests before deployment with `docker buildx imagetools inspect`. No image builds/pushes were performed during local code verification.

## 4. Start containers on the existing hosts

Store a one-line disposable MySQL password outside the repository and keep the private SSH key local. This is benchmark configuration, not a production secret system.

```sh
python3 infra/aws/scripts/deploy.py --inventory infra/aws/inventory.json \
  --key /absolute/path/to/private-key --tag YOUR_UNIQUE_TAG \
  --password-file /absolute/path/to/demo-db-password --model YOUR_CONVERSE_MODEL_ID --evaluation
```

The script copies benchmark/eval source, logs into ECR using each host's role, starts infra, waits for MySQL/Kafka, starts API (Flyway migration), then copilot/processors/gateways, and waits for health. nginx fronts the gateways with WebSocket upgrade headers and least-connections balancing. Roles use private addresses. Each gateway/fence-cache consumer has its own broadcast group; processors share `fleet-processor`. Re-running deployment replaces containers and resets the ephemeral Kafka container; it is not a rolling upgrade. MySQL uses a local Docker volume until EC2 is destroyed. Role credentials come from IMDS; no access keys are injected. Leave `--model` empty to keep copilot chat unconfigured (503) while services still start.

`--evaluation` exposes bounded tool arguments/results and usage in copilot replies solely for the disposable evaluation. Do not enable it for ordinary users. Port 8083 remains internal; the evaluator runs on an existing generator using that instance's role.

## 5. Dataset and collection

Use the same time-sorted NOAA subset as the laptop baseline; the exact download/subset commands and dataset checksums are in README's Phase 6 setup. Prefer `data/west-coast-4h.csv`, not the tiny 10-minute file. The harness copies the exact CSV and asset IDs to every generator and records SHA-256. A latency run requires at least `(seconds+5)*offered-rate` source rows; it fails if the supplied dataset is too short. S1 sends the full dataset at max rate with twelve MMSI-hashed sequential lanes.

Every trial deletes only the **disposable** fleet's state/topics, restarts the selected processor/gateway counts, records host CPU/cores/RAM/OS, Docker settings and chrony tracking/sources before/after, and samples each active host locally with a target interval of one second. `/proc/stat` supplies CPU/steal time, `/proc/net/dev` network counters, Docker stats container CPU/memory/network, Redis `INFO all` commandstats, and MySQL global status. Prometheus before/after deltas reuse `bench/metrics.py`. The Java lag observer records each partition and consumer member/host assignment once per loop. Host CPU is normalized across all cores; Docker CPU can exceed 100% when more than one core is busy. Actual timestamps, durations, overruns and collection errors are retained; blocking admin calls can exceed the requested second. Redis `cmdstat_eval`/`cmdstat_evalsha` usec deltas measure aggregate script execution, including token buckets, not an isolated per-script profiler.

All benchmark services have **tracing disabled** and each manifest states this. No synthetic latency is attributed to a shared clock: API ingestion and client receipt occur on separate hosts. Inspect chrony `System time`, last/RMS offsets and leap status on **every host** before/after. The harness rejects unsynchronized clocks or `System time` offsets above `--max-clock-offset-ms` (default 5 ms); the threshold is recorded in the manifest. Offsets large relative to p99 still invalidate fine-grained comparisons even below that threshold; retain such runs as failed/invalid rather than correcting numbers silently. Sample frequency and collection overhead are additional caveats.

## 6. Run the three-trial matrix

```sh
python3 bench/aws.py --inventory infra/aws/inventory.json --key /absolute/path/to/private-key \
  --tag YOUR_UNIQUE_TAG --dataset data/west-coast-4h.csv --reset-demo
```

`--reset-demo` explicitly acknowledges destructive demo data/topic reset. The harness uses existing instances only. It runs S1 with 1/2/4 processors; company-wide S2 at 5,000/10,000 clients with 1/2 gateways; and matched S3 with 0%/5% slow clients, three trials each. `--cases S1` can restrict the family. Two generators shard clients, each using bounded histograms and a 3 GiB JVM heap. A file barrier starts measurement only after all generator clients are connected. One generator replays so a vessel cannot cross sending processes out of order. S2/S3 offer 1000 positions/s against a much larger demo tenant quota, require zero API rate-rejection deltas and zero replay 429 retries, and run through nginx. Check sufficient descriptor/host/network headroom; failed connection counts invalidate the trial.

Raw artifacts go to `bench/results/AWS/<scenario>/trial-N/`: manifests, host setup/chrony text, before/after scrapes, one-second host samples, container stats, per-member lag, replay output, generator reports and histograms, and explicit outcomes. Fresh output folders are required so prior failures are not overwritten. At scale, the generator and SSH/collection overhead must be assessed as well as the services. Container/image/bootstrap failure remains visible; do not invent replacements for missing metrics.

```sh
python3 bench/report-aws.py
```

The report merges histogram bins across generators, then calculates p50/p95/p99 (never averages percentiles), and reports median/min/max across successful trials with the successful count and failed trials alongside. Compare processor throughput using the same full replay plus lag-drain interval. Before explaining a 1→2→4 result, inspect per-host CPU/steal, network rates, Redis commandstats/Lua time, MySQL counters and per-member lag/assignment. A busy shared dependency or unbalanced partitions is evidence only if the raw samples actually show it. No AWS scale-out explanation is asserted until this experiment runs.

## 7. Reproducible copilot data

After benchmarks, stop streaming and install fixed data:

```sh
python3 infra/aws/scripts/seed-eval.py --inventory infra/aws/inventory.json \
  --key /absolute/path/to/private-key --reset-demo
```

The script stops streaming and installs the deterministic fixture: **25 vessels across three tenants, eight seeded port circles, 100 crossings over seven days, latest positions, 68 confirmed asset/fence states, and an empty rule table**. Some vessels are inside, some at sea, some near but outside a port. Other-company canary names and a malicious Seattle display name are intentional. The fixture clock is **2024-01-08T12:00:00Z**. Exact data generation, ground truth, categories and limitations are documented in [copilot-eval/README.md](../../copilot-eval/README.md).

Before the evaluation, deploy with `--evaluation --fixed-now 2024-01-08T12:00:00Z`, then seed. Fixed time is optional and must not be applied to ordinary service use. The runner requires the same clock and model in the copilot audit/configuration and requires processors/gateways stopped. It reseeds before every task repetition, checks actual MySQL rule rows and restores API availability after each outage case. The evaluator's database connection is independent verification; it does not add shared-database access to the copilot.

## 8. Evaluate usefulness, calibrate the judge, then manually review

Use the **workstation**, so the private SSH key stays local and results are collected before destroy. Forward the copilot loopback port through SSH using its public IP from the Terraform inventory:

```sh
ssh -i ~/.ssh/fleet-bench -N -L 127.0.0.1:18083:127.0.0.1:8083 ec2-user@COPILOT_PUBLIC_IP
```

After Gate 3 approval, refresh model access and pricing and verify inference-profile destinations. Resources remain in us-east-1. DeepSeek invokes Bedrock directly in us-west-2; the copilot stays on its us-east-1 EC2 host. Deploy it with `--bedrock-region us-west-2 --model deepseek.v3-v1:0 --evaluation --fixed-now 2024-01-08T12:00:00Z`, then seed the fixture. For the Claude comparison, redeploy with `--bedrock-region us-east-1` and the Haiku model ID below, then seed again. The regional setting changes only copilot's Bedrock client. Do not add wildcard permissions or unverified profile destinations.

Run the DeepSeek pilot first from the workstation:

```sh
python3 copilot-eval/bedrock.py --url http://127.0.0.1:18083 \
  --inventory infra/aws/inventory.json --key ~/.ssh/fleet-bench \
  --model deepseek.v3-v1:0 --model-role target \
  --judge-model us.anthropic.claude-sonnet-4-6 --region us-east-1 \
  --prices 0.58 1.68 3.30 16.50 \
  --model-manifest infra/aws/evaluation-models.local.json \
  --purpose pilot --repeats 1 --task-ids atlas-status la-yesterday draft-la \
  --max-cost-usd 1 --output bench/results/AWS/bedrock-pilot-deepseek-1
```

After switching the copilot to `us.anthropic.claude-haiku-4-5-20251001-v1:0`, repeat the same pilot with `--model-role comparison`, `--prices 1.10 5.50 3.30 16.50` and output `bench/results/AWS/bedrock-pilot-claude-1`. If Haiku is unavailable, refresh the approved Sonnet fallback's access, exact ARNs and estimate before use. The runner first calibrates the judge on 10 bad / 4 good answers in each invocation. Any missed leak/fabrication stops that invocation; its verdicts must not be trusted.

**Stop after both pilots.** Report actual returned tokens and token cost, calibration results and remaining gross allowance, and wait for user go after their billing-console check. A pilot command never automatically proceeds to the full suite. Do not interpret account credits as permission to exceed $25.

After explicit full-evaluation approval, use the same model-specific arguments with `--purpose full --repeats 3`, omit `--task-ids`, and use outputs `bench/results/AWS/bedrock-deepseek-1` and `bench/results/AWS/bedrock-claude-1`. If the approved remaining budget calls for a one-repetition comparison, set Claude's `--repeats 1`; pass³ remains unmeasured. Choose `--max-cost-usd` from the remaining phase estimate, reserving host runtime and cleanup. No real calibration or task quality has yet been measured.

```sh
python3 copilot-eval/compare.py \
  bench/results/AWS/bedrock-deepseek-1 \
  bench/results/AWS/bedrock-claude-1 \
  --output bench/results/AWS/bedrock-eval-1/comparison.json
```

The fixture, questions, clock and judge must match. Reports show task success, pass³ where measured, leaks, latency, tokens and cost by category, with capability gaps separately listed. Tool selection remains diagnostic. Side effects are checked independently in MySQL; outages require actual unavailable-data evidence. Unknown failed-call usage stops further paid calls. Judge requests execute on the generator using its instance role; no credentials or private SSH keys are copied there. The internal copilot port remains closed to the internet.

The committed manifest contains placeholders; real account/profile ARNs stay in ignored `infra/aws/evaluation-models.local.json`. Raw pilot/full evidence stays ignored and is copied locally before destroy. Manual-review files remain PENDING templates. Planning cost is **$6.56** for both three-repetition suites and both pilots, **$4.14** with a one-repetition Claude comparison, or **$13.12** if the three-repetition plan's assumed tokens double. Host costs are additional; these are not measured spend or ceilings. See `copilot-eval/cost-estimate.json` and the whole-phase cost log.

## 9. Publish evidence honestly

Keep the original laptop results unchanged. Fill the separate README AWS table only from retained complete trials; include medians, min/max, all failures and chrony caveats. Attach resource evidence to the processor scale-out explanation: symptom → per-host evidence → supported conclusion. Distinguish automatic evaluation from manual review and estimated token cost from actual billed spend. Retain raw outputs locally; commit compact manifests and summaries rather than multi-thousand-line logs. Files containing passwords, IP inventory or Terraform state stay ignored.

## 10. Cleanup: operator only

- Copy results, seed manifest and manual reviews off the generators before destroying hosts.
- Inspect `terraform -chdir=infra/aws plan -destroy` and verify the intended fleet identities.
- Run `terraform -chdir=infra/aws destroy` yourself. ECR repositories use `force_delete=true`, so their pushed images are deleted too.
- Confirm EC2 termination and root volumes deleted; public IPv4s are auto-assigned, not persistent EIPs.
- Run `infra/aws/scripts/remaining-resources.sh us-east-1` and inspect tagged resources, volumes, snapshots and global IAM role/profile candidates. Terminated instances may still appear temporarily; active resources and leftover ECR storage require investigation.
- Recheck billing/budget alerts after billing data catches up. Do not delete Terraform state while cleanup remains incomplete.

The leftover audit is read-only and does not attempt to repair or destroy resources. Any failed destroy must be resolved by the operator; this project does not hide leftovers or automatically create replacement resources.

## Current Gate 3 override

The operator replaced Claude with DeepSeek-V3.1 (Oregon) and gpt-oss-120b (N. Virginia), each grading only the other target. The active evaluation manifest contains only these two models and IAM allows only their two exact foundation-model ARNs. Failed Claude agreement cancellation returned `No agreement exists to cancel`; no billing configuration or new Marketplace subscription is changed. Calibrate both judges before any pilot, reuse the calibration for all subsequent runs, and retain deterministic task/leak metrics when a judge fails. The current authorization permits continuing after pilots only if the actual-token forecast plus host/cleanup reserve remains below $12, with mandatory destruction within three hours from apply. Historical commands and Sonnet pricing above are superseded for this execution.


### Evaluation retry: verify before provisioning

The workstation first builds API/copilot with `docker buildx build --platform linux/arm64 --load` and starts them alongside ARM Kafka, Redis and MySQL. Run `python3 infra/aws/scripts/verify-local-images.py --tag <unique-tag> --output <ignored-results-directory>` before AWS preflight. Health checks must pass; the script removes its containers/network/volumes and checks that the normal Docker config is unchanged.

Only after that verification, plan/apply the two `aws_ecr_repository.service` resources using a targeted plan. Export repository-only inventory with `python3 infra/aws/scripts/repository-inventory.py --output infra/aws/repositories.local.json` (the full `inventory` output requires hosts and is unavailable after a targeted repository apply), and push the same verified local image tags with `push-images.sh`, then plan/apply the remaining evaluation resources. The push script no longer builds. Its temporary config copies only plugin search paths and uses an on-demand ECR credential helper; it never copies stored credentials or changes the user's config. The previous empty temporary config hid the normal `cli-plugins/docker-buildx` location and caused Docker to reject `--platform`.

Both judges use exactly one `submit_verdict` tool, `toolChoice.any` (so that sole tool must be called), `strict: true`, identical required fields and `additionalProperties: false`. Parsing independently validates one verdict and its types. Verify the request against both selected models before apply; calibrate each judge on all fourteen cases before either pilot. No text-JSON fallback is used. After any post-apply failure, copy existing evidence and destroy immediately.
