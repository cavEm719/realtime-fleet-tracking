# AWS Gate 3: two-model evaluation attempt

An operator asks the copilot about synthetic vessel locations, port arrivals or a draft alert. The intended evaluation checks whether its answers match code-derived facts and whether a draft actually appears in MySQL. This attempt stopped before the application was deployed, so it provides access/tool verification and cleanup evidence, **not task-success or judge-quality measurements**.

## What ran and where it stopped

The authorized root login, empty pending/running us-east-1 instance list and unchanged operator /32 were verified. Both selected models reported AVAILABLE agreements and authorization. Terraform applied the evaluation-only **18 add / 0 change / 0 destroy** plan: API, copilot, Kafka, Redis, MySQL and one runner. There were no processors, gateways or nginx. All resources were in us-east-1 and tagged `project=fleet-bench` where supported. IAM contained only these two InvokeModel resources:

```
arn:aws:bedrock:us-west-2::foundation-model/deepseek.v3-v1:0
arn:aws:bedrock:us-east-1::foundation-model/openai.gpt-oss-120b-1:0
```

Maven verification passed 139 tests. The ARM image step then exited 125:

```
unknown flag: --platform
Usage: docker [OPTIONS] COMMAND [ARG...]
```

The operator script's new temporary Docker credential configuration omitted buildx plugin discovery paths. Selecting the correct Docker socket was insufficient. This was an execution-script mistake introduced during preparation, not a model access error or application result. No application container was deployed; no fixture seed, fixed-clock verification, judge calibration, pilot or full run took place. The session stopped and cleaned up rather than continuing cloud execution after that failure.

## Model verification and pricing

The exact GPT OSS model is `openai.gpt-oss-120b-1:0`, not GPT OSS Safeguard. Live availability checks in us-east-1, us-east-2 and us-west-2 returned AVAILABLE agreement/entitlement/region and AUTHORIZED. No model-agreement creation or Marketplace subscription call was made for gpt-oss. AWS lists its product ID as N/A. [AWS model parameters](https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters-openai.html).

AWS documents in-region access in us-east-1, us-east-2, us-west-2, eu-central-1, eu-north-1, eu-south-1, eu-west-1, eu-west-2, ap-northeast-1, ap-south-1, ap-southeast-2, ap-southeast-3, sa-east-1, ap-southeast-4, ap-northeast-2, ap-southeast-1 and us-gov-west-1. Only N. Virginia was selected for this experiment. [Model card and region matrix](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-openai-gpt-oss-120b.html).

| Model / selected region | Standard input / output USD per million |
| --- | ---: |
| DeepSeek-V3.1 / Oregon | 0.58 / 1.68 |
| gpt-oss-120b / N. Virginia | 0.15 / 0.60 |

Rates were refreshed from the official AWS [Oregon price feed](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonBedrock/current/us-west-2/index.json) and [N. Virginia price feed](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonBedrock/current/us-east-1/index.json). GPT input/output SKUs are Q2U4FFKKTW34QVFG and KGHQD8ZHB5468Z38, charged at $0.00015/$0.0006 per thousand tokens. These are Standard on-demand rates, not batch, Flex, Priority or a different region's quote.

| Live gpt-oss verification | Returned input / output tokens | Price-derived cost USD | Outcome |
| --- | ---: | ---: | --- |
| Converse tool use | 138 / 47 | 0.00004890 | Correct lookup_asset name and assetId |
| outputConfig strict text JSON | 97 / 205 | 0.00013755 | Failed: returned an extra opening brace; JSON parser rejected it |
| submit_verdict tool JSON | 163 / 195 | 0.00014145 | Exact fields and boolean/string types validated |

AWS documents Converse, tooling and structured output capabilities, but the strict text test is retained as a failed measurement. [Converse tooling](https://docs.aws.amazon.com/bedrock/latest/userguide/tool-use-client-side.html), [structured output API](https://docs.aws.amazon.com/bedrock/latest/userguide/structured-output.html). The prepared evaluator uses typed submit_verdict tool input, never repairs malformed text or treats reasoning content as the verdict. This small verification establishes transport/schema behavior, not grading accuracy.

The two failed Claude agreement cancellation attempts each returned `ValidationException: No agreement exists to cancel`. No other Marketplace product, billing configuration or account setting was changed. Their ERROR access records were not presented as successfully deleted active subscriptions.

## Cross-judging and unmeasured results

The intended pairs are **DeepSeek target → gpt-oss judge**, and **gpt-oss target → DeepSeek judge**. A target never grades its own answers. Each judge has an independent fourteen-case calibration; pilot/full runs reuse that exact fixture/clock/rubric evidence. A missed leak/fabrication makes that judge untrusted and excludes its verdicts. Code-checked task success and answer/tool-result leaks remain the headline, with free-text faithfulness and clarification quality secondary. Different judges limit direct comparison; no judge accuracy is claimed in this attempt.

| Target | Judge | Planned task runs | Attempted | Code success / pass³ / leaks | Judge calibration accuracy | Latency / tokens / cost per task |
| --- | --- | ---: | ---: | --- | --- | --- |
| DeepSeek-V3.1 | gpt-oss-120b | 120 | 0 | NOT RUN | NOT RUN | NOT RUN |
| gpt-oss-120b | DeepSeek-V3.1 | 120 | 0 | NOT RUN | NOT RUN | NOT RUN |

There is no denominator for a task success or leak-rate claim. The successful small model-access calls are not operator-task completions, and zero executed calibration cases cannot establish judge trust.

| Category | Tasks per target | Success / pass³ / leaks / latency / tokens / task cost |
| --- | ---: | --- |
| lookup | 6 | NOT RUN |
| multi_step | 6 | NOT RUN |
| aggregation | 6 | NOT RUN |
| empty | 4 | NOT RUN |
| clarification | 3 | NOT RUN |
| write_action | 3 | NOT RUN |
| cross_tenant | 2 | NOT RUN |
| prompt_injection | 3 | NOT RUN |
| degraded_dependency | 3 | NOT RUN |
| multi_turn | 4 | NOT RUN |

Known tool capability gaps are fixture-defined, not measured model failures:

- `name-lookup`: No vessel-name lookup tool or vessel names in status responses.
- `inside-fleet`: No complete asset inventory or bulk confirmed-state listing; GEO discovery is not a complete confirmed-state inventory.
- `name-gap-followup`: No vessel-name resolver; asking for an MMSI is honest but does not complete the task.

The ignored local `bench/results/AWS/gate3-private/cross-judge/manual-review.json` has all 240 planned task/repetition entries, code-derived required facts, empty evidence and explicit NOT_RUN status. Every verdict remains **PENDING**; nothing is human-reviewed.

## Spend and cleanup

The revised planning assumption was $1.706550 for two three-repetition suites, pilots and calibration, plus a three-hour host reserve of $1.877104: approximately **$3.5841** including already returned verification usage. The assumption conservatively included repeated calibrations, although the final prepared harness reuses the initial two. It was not a spend ceiling or measured token forecast; no pilots ran to replace it with actual task usage.

- Apply began **2026-10-09T22:30:12.354827+00:00**; the three-hour deadline was **2026-10-10T01:30:12.354827+00:00**.
- Destroy completed **2026-10-09T22:33:54.020444+00:00**, well before the deadline.
- Host estimate from actual launch times to whole-fleet destroy completion: **$0.03337429**.
- Returned verification usage converted at list prices: **$0.00045990**, including the earlier two Claude OK checks ($0.000132).
- Estimated gross session spend: **$0.03383419**, versus the $12 limit. **Actual billed spend is unavailable**; returned token counts are measured, dollar amounts are list-price estimates. Credits are not subtracted.

All six host output archives were copied and their tar contents/SHA-256 verified. Since applications never started, these contain startup output rather than task evidence. Terraform destroyed **18 resources**; state is empty. The remaining-resources script exited 0 and directly reported:

```
EC2: 6 terminated
Volumes: []
Snapshots: []
Remaining fleet role/profile candidates: none
```

The tagging API still returned 19 instance and six volume records, including historical/deleted records; it is not an active-resource count. Direct EC2 queries showed only terminated instances and no tagged volumes/snapshots. No non-EC2 resource ARN remained in that response. The local watchdog stopped and cleanup automation is PAUSED. Raw access responses, price records, build output, archives and audit output remain ignored under the scenario folder. No credentials or private operator identifiers are committed.

Offline checks: Maven **139** tests (93 unit + 46 integration), evaluator **47**, benchmark tooling **23**, and Terraform **2** mocked plans passed. The public safety check ran before every commit. [Compact machine-readable evidence](../../bench/results/AWS/gate3-cross-summary.json) retains setup, failure, usage, costs and cleanup distinctions.
