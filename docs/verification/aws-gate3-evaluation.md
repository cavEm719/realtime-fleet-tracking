# AWS copilot evaluation: completed DeepSeek run and partial GPT run

The operator asks where vessels are, which ports they visited, or requests a draft alert. The copilot resolves these requests using tenant-scoped tools supplied by the API. This evaluation checks the resulting answer against deterministic vessel, port and event facts, verifies draft rows in MySQL, and checks both answers and tool results for foreign-tenant identifiers. Calling a tool alone does not count as success.

## Outcome and comparability

Both 14-case judge calibrations and both three-task pilots finished before the full runs. DeepSeek completed all 40 tasks × 3 fresh conversations. GPT completed 18 responses, then the first repetition of `la-yesterday` returned HTTP 502 without token usage. Its run stopped, all retained evidence was copied, and the fleet was destroyed. **The requested complete two-model comparison did not finish.** No unrun category is reported as passing, and the GPT prefix cannot establish which model is better.

| Target | Attempted / planned | Code-checked success | Three-pass tasks | Strict leaked runs | Mean completed request latency | Known target input / output tokens | Known target token cost |
|---|---:|---:|---:|---:|---:|---:|---:|
| DeepSeek-V3.1 | 120/120 | 47.5% | 14/40 | 3 | 2.569 s | 432,271 / 34,179 | $0.308138 |
| gpt-oss-120b | 19/120 | 68.4% | 3/6 completed tasks (partial) | 0 | 4.100 s | 31,278 / 8,229 | $0.009629 |

GPT success is 13/19 attempted tasks, including the failed HTTP request; among the 18 complete responses it is 13/18. Zero prefix leaks does not establish safety on its unrun cross-tenant or injection tasks. DeepSeek’s three strict leak hits were refusals echoing the foreign MMSI already supplied in the prompt; those responses made no tool calls. They remain failures under the approved no-foreign-identifier rule, rather than being reclassified as zero leaks.

## Category results

Costs include returned target and task-judge tokens, excluding standalone calibration. Latency is elapsed copilot HTTP request time, not benchmark pipeline latency. GPT has no paid task-judge calls because its proposed judge failed calibration. Unmeasured categories are shown explicitly.

| Category | DeepSeek successes/runs | DeepSeek pass³ tasks | DeepSeek leaks | Mean latency | Mean tokens/run | Mean token cost/run | GPT successes/runs |
|---|---:|---:|---:|---:|---:|---:|---:|
| lookup | 11/18 | 3/6 | 0 | 2.150 s | 4197.2 | $0.002118 | 13/18 |
| multi_step | 11/18 | 2/6 | 0 | 2.639 s | 6276.7 | $0.003122 | 0/1 |
| aggregation | 0/18 | 0/6 | 0 | 3.088 s | 10889.0 | $0.004735 | Not measured |
| empty | 5/12 | 1/4 | 0 | 2.512 s | 4684.2 | $0.002439 | Not measured |
| clarification | 6/9 | 2/3 | 0 | 1.484 s | 4517.4 | $0.002083 | Not measured |
| write_action | 4/9 | 1/3 | 0 | 2.653 s | 4958.1 | $0.002568 | Not measured |
| cross_tenant | 3/6 | 1/2 | 3 | 2.370 s | 3178.8 | $0.001736 | Not measured |
| prompt_injection | 3/9 | 1/3 | 0 | 1.573 s | 3524.8 | $0.001756 | Not measured |
| degraded_dependency | 5/9 | 0/3 | 0 | 1.721 s | 8884.0 | $0.005375 | Not measured |
| multi_turn | 9/12 | 3/4 | 0 | 4.605 s | 7573.2 | $0.004185 | Not measured |

For the GPT lookup prefix: 13/18 successes, 3/6 tasks pass³, zero strict leaks; the remaining attempted multi-step request failed before returning an answer. See the compact summary for category token costs, per-task pass counts and every pending review entry.

## Judge calibration and measurement corrections

gpt-oss-120b correctly classified **14/14** calibration answers, including every leak and fabrication, and served as DeepSeek’s trusted secondary judge. DeepSeek classified **13/14**, accepted the fabricated-event case, and was excluded as GPT’s judge. Thus the targets had different proposed cross-judges, but only DeepSeek has usable secondary model verdicts. Code checks are the headline for both; no answer is marked human-reviewed.

Both judges used the sole forced `submit_verdict` tool with a strict boolean/string schema. One GPT task-judge response exhausted its initial 1,024-token output cap without a verdict. Its receipt (2,737 input / 1,024 output tokens, $0.001025) is retained and charged; no verdict was invented or retried. The judge cap was raised to 4,096 for subsequent calls, and a checkpoint resumed only unfinished tasks. Calibration used the original 1,024 cap; the rubric/schema and target prompts/settings did not change. This difference is a calibration caveat.

Two offline check defects were fixed with regression tests: ordinary phrases such as “vessel appears” were mistaken for invented names, and a numbered list on a new line after “port fence” was mistaken for a foreign fence ID. Both targets were regraded from retained answers/tool results using the same final checker, with original grades and source hashes preserved. This made no AWS calls. DeepSeek’s retained original scores were 47/120 successes with six leak flags; uniform regrading yields 57/120 successes and three leak flags. Three newline false positives were removed, and ordinary-prose name errors were corrected; genuine foreign-identifier echoes still fail. GPT’s retained prefix stays 13/19 with zero leak flags.

Three tool capability gaps remain in the denominator: lookup by vessel name, a complete fleet-wide confirmed-state inventory, and a name-based follow-up. They are reported separately rather than rewriting realistic questions. Failed count/time summaries, draft scope checks, empty answers and language checks remain visible in the evidence; free-text checker judgments still require manual review.

## Setup, cost and cleanup

The fixture has 25 vessels across three tenants, eight fences, 100 events over seven days, foreign canary names and injected instructions in data. Each task starts a fresh conversation; fixture reseeding and database snapshots prevent draft state carrying over. The service clock is fixed to `2024-01-08T12:00:00Z`; tracing is disabled. API and copilot images are the locally verified ARM images tagged `gate3-retry-1`. Six hosts ran: API c7g.large, copilot/Kafka/Redis m7g.large, MySQL r7g.large, runner c7g.xlarge. No processor, gateway or nginx host was created.

DeepSeek runs in us-west-2 at $0.58 input/$1.68 output per million tokens; GPT runs in us-east-1 at $0.15/$0.60. All resources were created in us-east-1. The pilot projection for both full runs was $0.417804 in token costs; the conservative whole-session continuation estimate was $3.630792, below $12. Known full-run token costs were $0.375488 for DeepSeek plus its GPT judge and $0.009629 for the partial GPT target. Standalone GPT/DeepSeek judge calibration cost $0.005333/$0.012702; the DeepSeek/GPT three-task pilots cost $0.007900/$0.002546. These are additional to full-run costs and recorded in the cost log. One failed GPT request has unknown usage, so returned-token totals are a lower bound, not a complete actual invoice. Credits are not deducted from gross estimates.

Local rehearsal passed four mock Terraform plans, exports from saved plan JSON, image-push dry-run and deployment dry-run before any AWS call. Live ECR plan was 2 additions; remaining plan 16 additions; neither changed nor destroyed resources. An ECR-only wrapper classification error was repaired without destroying repositories. A costed missing judge verdict was repaired within the 20-minute window. The later target 502 provided no recoverable usage or specific Bedrock error in service logs; it was not retried.

Results, host/container logs, clock evidence and cloud-init logs were copied before destruction. Cleanup and final verification are recorded below; raw multi-thousand-line logs stay ignored. The compact manual-review template contains all 240 planned task/repetition slots, including unattempted slots, and remains PENDING.


## Final cleanup and verification

`terraform destroy` completed at 2026-10-10T01:29:17.784582+00:00 UTC: **18 resources destroyed**, exit 0; `remaining-resources.sh` exited 0. Its EC2 list contains six terminated instances (IP null); `Volumes: []` and `Snapshots: []`. The tag API still shows historical deletion records, which are not proof of live resources. Follow-up exact project-scoped checks returned:

```json
{
  "vpcs": 0,
  "securityGroups": 0,
  "activeInstances": 0,
  "ecr-api": "absent",
  "ecr-copilot": "absent"
}
```

Terraform state contains zero resources. All six host archives were copied successfully before destruction, with byte counts and SHA-256 records retained locally. The tunnel and local watchdog are stopped. No fleet is left running.

The launch-time/hourly-price estimate plus all returned token receipts and the prior failed-attempt reserve totals **$0.962412**. Because one failed target request has unknown usage, reserve an additional **$1.00** for that call and small unmetered storage/transfer charges: conservative working total **$1.962412**, below $12. This reserve is not a measured charge. Neither billing-console reconciliation nor credits coverage was verified; a complete actual invoice cannot be claimed.

Final `mvn -B -ntp verify`: **BUILD SUCCESS; 139 tests, zero failures/errors/skips**, all eight reactor entries successful. Offline evaluator tests: 54 passed; benchmark/deployment tests: 27 passed; four mock Terraform plans passed. The publication check is run before each commit. [Compact summary](../../bench/results/AWS/bedrock-eval-2/summary.json) retains per-task/category results and calibration cases; [manual review](../../bench/results/AWS/bedrock-eval-2/manual-review.json) remains PENDING for all 240 planned slots. Raw files are under ignored `bench/results/AWS/gate3-private/retry-2/`, including original grades, regrade hashes, checkpoint, receipts, host logs and full cleanup output.


Round-2 preparation added a second disclosure metric without changing round-1 scores: strict leaked runs remain DeepSeek 3 / GPT prefix 0; **data leaked runs are DeepSeek 0 / GPT prefix 0** when user-supplied identifier echoes are excluded. Foreign tool disclosures would still count. Per-category values are added to the compact summary; all are zero on the retained round-1 evidence. The GPT prefix still did not test the full safety suite. [Failure analysis and frozen-evaluation hypotheses](copilot-round2-local.md) cover all 63 failed DeepSeek repetitions.
