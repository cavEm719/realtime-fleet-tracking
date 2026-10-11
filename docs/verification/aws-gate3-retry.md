# Gate 3 retry: local verification passed, ECR-only stage stopped

The evaluation asks the copilot to answer questions about synthetic vessel locations and port arrivals. Each target is intended to be graded by the other model, with code-derived task success and leakage checks as the headline. This retry never reached judge calibration or operator tasks.

## Local preparation

API and copilot were built with `docker buildx build --platform linux/arm64 --load`, without pushing. Both started successfully alongside ARM MySQL, Redis and Kafka; health probes passed. The temporary local containers/network/volumes were removed. The normal Docker configuration was unchanged.

The previous push script set `DOCKER_CONFIG` to an empty temporary directory, hiding the normal configuration's buildx plugin lookup path. The new temporary configuration retains only those plugin search paths, uses the on-demand ECR helper, copies no stored authentication entries, and is removed on exit. Push now tags and uploads verified images without rebuilding. A regression test verifies config preservation, temporary-directory removal and absence of a build operation.

Both judges now use the same single `submit_verdict` tool, `toolChoice.any`, `strict: true` and a schema requiring exactly `faithful` (boolean), `clarificationAcceptable` (boolean) and `reason` (string), with no extra fields. Since only one tool exists, a forced tool choice selects that verdict. Parsing separately enforces the same types. Both models accepted the request live; these smoke calls do not establish judge accuracy.

| Model | Region | Returned input/output | Price-derived USD |
| --- | --- | ---: | ---: |
| gpt-oss-120b | us-east-1 | 424 / 135 | 0.00014460 |
| DeepSeek-V3.1 | us-west-2 | 479 / 77 | 0.00040718 |

Previously verified standard rates remain $0.15/$0.60 and $0.58/$1.68 per million input/output tokens respectively. [Official AWS N. Virginia prices](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonBedrock/current/us-east-1/index.json), [Oregon prices](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonBedrock/current/us-west-2/index.json). No new Marketplace agreement or billing change was made.

## Preflight, failure and cleanup

Approved root identity, zero pending/running us-east-1 instances, unchanged operator /32, and both models AVAILABLE/AUTHORIZED were verified. ECR-only plan was **2 additions / 0 changes / 0 deletions**; full plan before this apply was **18 / 0 / 0**. First apply began **2026-10-10T00:19:03.292031+00:00**, with three-hour deadline **2026-10-10T03:19:03.292031+00:00**.

Two repositories were created. The following attempt to export the full Terraform `inventory` returned exit 1 because that output depends on hosts that were excluded from the targeted apply. This was an operator orchestration mistake. No image push or host provisioning followed. Per the authorization, execution stopped and immediately destroyed both repositories; cleanup completed **2026-10-10T00:19:18.340144+00:00**. No EC2 was created and there was no host output to copy; all local logs and model responses are retained under the ignored retry results directory.

After cleanup, a local repository-only exporter was added. It reads just the two repository addresses from state, without depending on full inventory outputs. Its test uses a state containing only repositories and no outputs/hosts. This correction has **not** been retried against AWS.

Audit output (private identifiers omitted):

```text
Destroy complete! Resources: 2 destroyed.
Terraform state: empty
EC2 direct query: []
Volumes: []
Snapshots: []
Remaining fleet IAM role/profile candidates: none
Audit exit: 0
```

The tagging API returned 15 historical tag records of kinds instance. Those are not an active-resource count; direct checks and empty state are recorded separately. The watchdog was stopped. No fleet is running.

## Cost and results

New returned-usage token estimate: **$0.00055178**. Cumulative conservative estimate including the previous failed fleet attempt: **$0.03438597**, below $12. No EC2 runtime or uploaded ECR image storage was incurred in this retry. Actual billed total is unavailable; credits are not subtracted. Planned full cross-judged run remains approximately $3.59 including a three-hour host reserve, but no pilot has supplied real task usage.

Both fourteen-case calibrations, both pilots and both full suites are **NOT RUN**. Task success, pass³, leaks, calibration accuracies and per-task latency/tokens/cost are unmeasured. The pending manual-review template from the previous attempt remains PENDING, with no human review or provisional model verdicts.

Verification: `mvn -B -ntp verify` passed **139 tests**; evaluator **48 tests**, benchmark tooling **25 tests** passed. The public safety check passed before each commit. [Compact evidence](../../bench/results/AWS/gate3-retry-summary.json).
