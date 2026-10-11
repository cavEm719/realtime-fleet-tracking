# AWS copilot improvement round 2

An operator asks where owned vessels are, counts port visits, or drafts an alert. The copilot reads tenant-scoped API tools and answers from those records. This round adds missing reads and server-side counting/time conversion, then checks whether the answers improve on the same fixed tasks. Tasks, fixture, ground truth and strict success checks were not changed.

## Measured outcome

All verdicts remain provisional and all human reviews are PENDING. The GPT round-1 baseline ended after 19 attempted runs, so it is a partial prefix, not a complete model comparison. Historical capability-gap flags remain in the frozen task metadata and still force failures under the original strict scorer; rows meeting all fact checks despite that flag are listed separately in the summary.

| Model / round | Attempted | Successes | pass³ tasks | Strict leak runs | Data leak runs | Runtime failures |
|---|---:|---:|---:|---:|---:|---:|
| DeepSeek-V3.1 / 1 | 120/120 | 57/120 (47.5%) | 14 | 3 | 0 | see baseline evidence |
| DeepSeek-V3.1 / 2 | 120/120 | 59/120 (49.2%) | 16 | 3 | 0 | 2 |
| gpt-oss-120b / 1 | 19/120 | 13/19 (68.4%) | 3 | 0 | 0 | see baseline evidence |
| gpt-oss-120b / 2 | 120/120 | 45/120 (37.5%) | 8 | 0 | 0 | 17 |

Strict leakage includes foreign identifiers supplied by the user. Data leakage excludes those user-supplied echoes in model text, but never excludes foreign records returned by tools. This changes neither required facts nor the original success score.

## Categories

Each cell is successful runs / attempted runs; unrun round-1 GPT categories remain unmeasured. Detailed pass³, both leak metrics, latency, tokens and per-run cost are in the compact summary. Costs use returned usage and exclude standalone calibration; request latency measures the copilot HTTP request, not vessel pipeline latency.

| Category | DeepSeek R1 | DeepSeek R2 | GPT R1 (partial) | GPT R2 |
|---|---:|---:|---:|---:|
| lookup | 11/18 | 9/18 | 13/18 | 7/18 |
| multi_step | 11/18 | 15/18 | 0/1 | 5/18 |
| aggregation | 0/18 | 0/18 | Not measured | 4/18 |
| empty | 5/12 | 9/12 | Not measured | 6/12 |
| clarification | 6/9 | 3/9 | Not measured | 5/9 |
| write_action | 4/9 | 3/9 | Not measured | 4/9 |
| cross_tenant | 3/6 | 3/6 | Not measured | 3/6 |
| prompt_injection | 3/9 | 5/9 | Not measured | 5/9 |
| degraded_dependency | 5/9 | 2/9 | Not measured | 0/9 |
| multi_turn | 9/12 | 10/12 | Not measured | 6/12 |

## Failure causes and tool hypotheses

For the final before/after table both rounds use the refined same classifier. The preserved initial DeepSeek triage had 2 argument / 25 time / 20 other primary failures; recognizing correct report-time prose and another rejected-argument receipt changes these to 3 / 23 / 21, without changing any success score or raw answer.

The run-by-run failure files retain answer/tool excerpts, original checker errors and source-record hashes. Primary counts assign one cause per failed run; additional labels are also retained. They are evidence-based hypotheses, not completed manual judgments. Four tool changes were bundled, so differences do not isolate a causal effect of one tool. No supported 50-row truncation was found in round 1, and pagination was not added.

| Primary cause | DeepSeek R1 | DeepSeek R2 | GPT R1 (partial) | GPT R2 |
|---|---:|---:|---:|---:|
| missing/insufficient tool | 9 | 0 | 3 | 0 |
| 50-row cap | 0 | 0 | 0 | 0 |
| wrong tool | 0 | 3 | 0 | 0 |
| wrong arguments | 3 | 2 | 0 | 16 |
| time-range conversion | 23 | 0 | 0 | 0 |
| counting/arithmetic | 7 | 0 | 0 | 0 |
| fabrication | 0 | 0 | 0 | 0 |
| refusal when answer possible | 0 | 0 | 0 | 0 |
| other | 21 | 56 | 3 | 59 |

Before/after hypotheses: `findAssets` resolves owned vessel names; `getFleetState` reads confirmed INSIDE counts instead of substituting proximity; `aggregateEvents` counts complete SQL matches rather than counting capped detail lists; `resolveTimeRange` converts natural UTC windows from the fixed server clock. Each tool injects the tenant server-side, validates filters/ranges and caps detail output. The local hypothesis document explains the evidence and tests. Residual failures and checker/prose mismatches are preserved rather than rewriting questions.

## Judges, retries and incidents

GPT judges DeepSeek using the sole forced strict `submit_verdict` tool. The proposed different-provider judges for GPT were attempted in order: Mistral Large 2407, then Llama 4 Maverick. Both returned ValidationException on the first calibration request; neither completed the 14 cases. This is a rejected calibration call, not an observed 0% judgment accuracy. Neither judge verdict is used for GPT. No Marketplace subscription or billing setting was changed.

- gpt: 14/14 attempted; trusted=True; planned cases=14.
- mistral: 0/1 attempted; trusted=False; planned cases=14.
- maverick: 0/1 attempted; trusted=False; planned cases=14.

Target and judge transient 5xx/throttling calls retry once with backoff. Target retry repeats only the same model prompt, never the whole conversation or a completed draft write. Attempts, returned usage and failures are retained; failed runs remain failures and subsequent tasks proceed. Known pre-inference ValidationException rejections are distinguished from genuinely unknown paid attempts, which retain a $0.60 reserve each.

The seed command first omitted its required `--reset-demo` flag and exited before writing data. GPT calibration started before this was repaired; it evaluates local fixed calibration answers and makes no API data query. Fixture seeding and clock verification completed before any operator task. A pilot-specific $1.50 guard stopped GPT after two tasks. Recovery exposed an old no-usage resume restriction and an assumption that judge receipts always exist; both were fixed/tested inside the 20-minute window. Completed tasks were not repeated. The third pilot task was then completed with a larger sublimit within the unchanged $12 session budget.

## Cost, setup and cleanup

Fixed fixture: 25 vessels, 8 fences, 100 events over 7 days, 68 confirmed rows; clock 2024-01-08T12:00:00Z; tracing disabled. Each repetition starts a fresh conversation and reseeds deterministic database/Redis data. Six Graviton hosts only: API c7g.large, copilot/Kafka/Redis m7g.large, MySQL r7g.large, runner c7g.xlarge. Containers use verified local ARM images tagged gate3-round-2. The combined host estimate is $0.625701369863/hour, including EC2, EBS and IPv4.

DeepSeek in us-west-2: $0.58/$1.68 per million input/output tokens; GPT in us-east-1: $0.15/$0.60. Proposed Mistral judge: $2/$6; Maverick: $0.24/$0.97. Exact allowed ARNs, including the Maverick US profile destination regions, were used without a model wildcard. AWS official regional pricing catalogs were retained locally.

Raw outputs, attempts, receipts, logs and host copies remain in ignored bench/results/AWS/gate3-private/round-2/. Compact summary, failure evidence and PENDING review templates are committed. Launch-time/returned-token costs are estimates, not a verified invoice or a claim that credits covered them. Final cleanup/test evidence is appended after destruction.

## Matched baseline slots and tool observations

- DeepSeek-V3.1: same 120 retained slots, round 1 57 successes, round 2 59.
- gpt-oss-120b: same 19 retained slots, round 1 13 successes, round 2 7.

These task groups were selected from the recorded tool hypotheses. They overlap other metrics and do not isolate one change. `All fact checks` omits only the historical capability-gap veto; it does not relax leak, count, time, language or side-effect checks.

| Hypothesis / tool | R1 strict / runs | R2 strict / runs | R1 all fact checks | R2 all fact checks | R2 used tool |
|---|---:|---:|---:|---:|---:|
| findAssets | 0/6 | 0/6 | 0 | 2 | 6/6 |
| getFleetState | 0/3 | 0/3 | 0 | 0 | 3/3 |
| aggregateEvents | 0/15 | 0/15 | 0 | 0 | 15/15 |
| resolveTimeRange | 11/18 | 18/18 | 11 | 18 | 18/18 |

The count checker rejects some correct additional SQL breakdown scopes, and the timestamp checker rejects correct latest-report time when a different transition timestamp is also present. Those scores remain failures. The cause files distinguish these from proved arithmetic/time mistakes; secondary judge verdicts and manual review evidence must not silently replace the headline.

## Round-2 category details

Latency is the sum of target HTTP time for the task conversation, excluding fixture reset and outage-control setup. Tokens/cost include returned target setup calls and task judging.

| Model / category | Successes/runs | pass³/tasks | Strict/data leaked runs | Mean latency s | Mean tokens/run | Mean token cost/run USD |
|---|---:|---:|---:|---:|---:|---:|
| DeepSeek-V3.1 / lookup | 9/18 | 2/6 | 0/0 | 2.831 | 5011.3 | 0.002581 |
| DeepSeek-V3.1 / multi_step | 15/18 | 5/6 | 0/0 | 3.802 | 12045.8 | 0.006344 |
| DeepSeek-V3.1 / aggregation | 0/18 | 0/6 | 0/0 | 2.256 | 7777.9 | 0.003424 |
| DeepSeek-V3.1 / empty | 9/12 | 3/4 | 0/0 | 2.474 | 6999.2 | 0.003753 |
| DeepSeek-V3.1 / clarification | 3/9 | 1/3 | 0/0 | 2.283 | 5156.8 | 0.002722 |
| DeepSeek-V3.1 / write_action | 3/9 | 1/3 | 0/0 | 2.383 | 6402.4 | 0.003394 |
| DeepSeek-V3.1 / cross_tenant | 3/6 | 1/2 | 3/0 | 2.294 | 4157.3 | 0.002281 |
| DeepSeek-V3.1 / prompt_injection | 5/9 | 1/3 | 0/0 | 2.928 | 4544.6 | 0.002343 |
| DeepSeek-V3.1 / degraded_dependency | 2/9 | 0/3 | 0/0 | 2.845 | 14085.2 | 0.008300 |
| DeepSeek-V3.1 / multi_turn | 10/12 | 2/4 | 0/0 | 14.777 | 15029.5 | 0.008470 |
| gpt-oss-120b / lookup | 7/18 | 1/6 | 0/0 | 3.137 | 2450.9 | 0.000484 |
| gpt-oss-120b / multi_step | 5/18 | 1/6 | 0/0 | 9.110 | 4486.8 | 0.001014 |
| gpt-oss-120b / aggregation | 4/18 | 0/6 | 0/0 | 2.991 | 2216.6 | 0.000464 |
| gpt-oss-120b / empty | 6/12 | 2/4 | 0/0 | 4.060 | 3431.4 | 0.000693 |
| gpt-oss-120b / clarification | 5/9 | 1/3 | 0/0 | 3.585 | 2957.2 | 0.000641 |
| gpt-oss-120b / write_action | 4/9 | 0/3 | 0/0 | 4.179 | 2462.1 | 0.000534 |
| gpt-oss-120b / cross_tenant | 3/6 | 1/2 | 0/0 | 2.983 | 1746.5 | 0.000373 |
| gpt-oss-120b / prompt_injection | 5/9 | 1/3 | 0/0 | 3.915 | 3066.7 | 0.000664 |
| gpt-oss-120b / degraded_dependency | 0/9 | 0/3 | 0/0 | 7.749 | 15456.2 | 0.002967 |
| gpt-oss-120b / multi_turn | 6/12 | 1/4 | 0/0 | 7.711 | 7268.3 | 0.001468 |

Official regional prices: [us-east-1](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonBedrock/current/us-east-1/index.json), [us-west-2](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonBedrock/current/us-west-2/index.json).

## Returned usage and latency comparison

Mean duration uses retained target-conversation measurements, including failed requests when measured; sample counts are explicit. Known target costs exclude judge tokens and do not call missing usage zero. The GPT round-1 prefix is not a full-run baseline.

| Model / round | Measured duration samples | Mean seconds | Known target input/output tokens | Known target token cost USD |
|---|---:|---:|---:|---:|
| DeepSeek-V3.1 / 1 | 120/120 | 2.569 | 432271/34179 | 0.308138 |
| DeepSeek-V3.1 / 2 | 120/120 | 3.956 | 730676/31773 | 0.477171 |
| gpt-oss-120b / 1 | 19/19 | 4.019 | 31278/8229 | 0.009629 |
| gpt-oss-120b / 2 | 120/120 | 5.069 | 454997/64136 | 0.106731 |

A correct extra breakdown is not proof of bad arithmetic, and the analysis labels it accordingly. No fabrication is declared merely because a deterministic check failed. The full retained answer/tool facts remain available for every PENDING review.

## Final verification, corrected budget and cleanup

`mvn -B -ntp verify`: BUILD SUCCESS, 145 tests, zero failures/errors/skips. Evaluator checks: 71 passed; deployment/benchmark checks: 27 passed. Four mocked Terraform plans and ARM startup/dry-run checks passed before AWS calls. Main service source stayed at a3514a6 throughout paid evaluation; runner source loaded before later offline fixes is recorded in the summary. Retained failed-turn tool data was audited offline; this added no strict leak flags and changed no score. Tasks, fixture and ground truth have no diff. Docker config was unchanged by local image verification; no operator Docker-config edits remain.

All **18 resources destroyed** (exit 0); remaining-resources script exit 0. Active EC2/VPC/subnet/SG/address/key-pair/ECR/state lists are empty, as are EBS/snapshots. The tagging API retains 20 historical cache records (14 instance, 6 volume); the current six instances are terminated. Full sanitized script output and follow-up lists are in `audit-output.json`. All six host archives were copied before deletion, with hashes in `cleanup.json`. The extra parallel read audit hit native-login OAuth refresh throttling (429); a sequential read-only retry with the same session passed. No alternative credential source was used.

Session lasted 76.50 minutes from first ECR apply, within three hours. **Known launch-time/returned-token estimate $1.425168; unknown-use reserve $9.00; combined $10.425168, below $12.** Actual invoiced spend and credit coverage remain unknown. Host costs conservatively use each actual launch time through cleanup completion. Pilot extrapolation was $2.455564 before separate calibration/unknown reserves, not a measured full-run prediction.

The final audit corrected an estimate defect: two DeepSeek multi-turn requests returned 504 on a follow-up, but earlier turns had usage receipts, which hid those missing follow-up reserves. Earlier displayed $4.425168 included only the GPT 504 reserve; it is superseded by **$10.425168**, covering all three missing turn receipts at $3 each. Returned usage and known costs did not change. The defect has a regression test; the corrected estimate still stays below $12 and the fleet is already destroyed. No unknown usage is presented as zero cost.

Target Bedrock defaults were unchanged: temperature 0, 1,024 output tokens, 30-second overall request deadline and eight tool calls; judge output cap 4,096. The added schemas/results increase context: DeepSeek known target tokens rose from 432,271 input / 34,179 output to 730,676 / 31,773, and known target cost from $0.308138 to $0.477175 for only two additional strict successes. The result does not establish a worthwhile cost/quality improvement.

The `other` bucket is deliberately not renamed as fabrication: 15 DeepSeek failures have correct additional SQL counts rejected by the scope checker, three include the correct source-report timestamp with contextual timestamps, and three are strict user-identifier echoes. Nine also retain historical gap/missing-fact constraints; the remaining cases include prose, language, side-effect and timeout failures. These are hypotheses with evidence, awaiting review. Identical primary-category counts are available for both rounds/models in the failure JSON files.
