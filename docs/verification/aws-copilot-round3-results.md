# Round 3 evaluation report

Operators use this assistant to locate vessels, review port visits, and draft stored alert rules. Round 3 fixes model-facing UTC times, stops further data requests after an outage, and explicitly states that rules never notify. The checker is unchanged from the independently validated revision.

## Results and versions

Round 2 uses task version 1; round 3 uses version 2. Two definitions changed: outage-chinese now specifies 5 km and foreign-port-empty requires the unowned-port fact. The other 38 tasks and the fixture are unchanged. Overall totals are shown for context; the matched 38-task comparison isolates this version difference. The original generated manual-review templates remain PENDING; the independently supplied round-3 review is now recorded in the addendum below.

| Model | Round 2 solved | Round 3 solved | Matched 38: R2 → R3 | Strict leaks R2 → R3 | Data leaks R2 → R3 | Detected UTC errors R2 → R3 |
|---|---:|---:|---:|---:|---:|---:|
| DeepSeek-V3.1 | 113/120 | 109/120 | 107/114 → 103/114 | 3 → 3 | 0 → 0 | 14 → 0 |
| gpt-oss-120b | 98/120 | 113/120 | 95/114 → 107/114 | 0 → 0 | 0 → 0 | 18 → 0 |

Detected UTC errors are the same offline diagnostic on both rounds. It catches explicit full date/time conversion mismatches, using minute precision. Dates without times, implicit dates, and exact vessel-to-time attribution are not exhaustive; these counts do not establish full accuracy.

## Category comparison

Each cell shows solved / attempted and the number of tasks passing all three trials. The empty and degraded-dependency categories each contain one revised task, so use the matched subset in comparison.json when attributing differences.

| Category | DeepSeek R2 | DeepSeek R3 | gpt-oss R2 | gpt-oss R3 |
|---|---:|---:|---:|---:|
| aggregation | 18/18; pass³ 6/6 | 16/18; pass³ 5/6 | 18/18; pass³ 6/6 | 18/18; pass³ 6/6 |
| clarification | 9/9; pass³ 3/3 | 9/9; pass³ 3/3 | 9/9; pass³ 3/3 | 8/9; pass³ 2/3 |
| cross_tenant | 6/6; pass³ 2/2 | 6/6; pass³ 2/2 | 6/6; pass³ 2/2 | 6/6; pass³ 2/2 |
| degraded_dependency | 8/9; pass³ 2/3 | 9/9; pass³ 3/3 | 5/9; pass³ 1/3 | 9/9; pass³ 3/3 |
| empty | 12/12; pass³ 4/4 | 12/12; pass³ 4/4 | 12/12; pass³ 4/4 | 11/12; pass³ 3/4 |
| lookup | 18/18; pass³ 6/6 | 17/18; pass³ 5/6 | 15/18; pass³ 4/6 | 18/18; pass³ 6/6 |
| multi_step | 15/18; pass³ 5/6 | 12/18; pass³ 4/6 | 11/18; pass³ 2/6 | 14/18; pass³ 4/6 |
| multi_turn | 10/12; pass³ 2/4 | 10/12; pass³ 3/4 | 9/12; pass³ 2/4 | 12/12; pass³ 4/4 |
| prompt_injection | 9/9; pass³ 3/3 | 9/9; pass³ 3/3 | 8/9; pass³ 2/3 | 8/9; pass³ 2/3 |
| write_action | 8/9; pass³ 2/3 | 9/9; pass³ 3/3 | 5/9; pass³ 0/3 | 9/9; pass³ 3/3 |

## Calibration, latency and cost

gpt-oss graded DeepSeek using the forced verdict tool. Mistral Large and then Maverick were checked as different-provider judges for the gpt-oss target. Their rejected requests produced no judgments: calibration accuracy is unavailable, not a measured 0%. No self-judging or uncalibrated verdict is used. Headline solved and leak counts are deterministic.

| Target | Trusted judge | Mean request latency (s) | Target input / output tokens | Task judging input / output | Returned-token cost (USD) |
|---|---|---:|---:|---:|---:|
| DeepSeek-V3.1 | gpt-oss, 14/14 | 2.898 | 748403 / 30702 | 210897 / 45305 | 0.544471 |
| gpt-oss-120b | Unavailable; deterministic only | 4.649 | 538117 / 70431 | 0 / 0 | 0.122976 |

Host launch-time estimates, calibration/pilot usage, reserves and cleanup are recorded in cost-log.md. Returned-token costs are estimates from approved prices, not a billing invoice. Raw answers, tool results, usage receipts and PENDING manual-review templates remain in ignored round-3 result folders. Original round-1/2 results and independent labels remain unchanged.


## Category latency, tokens and cost

Latency is the measured sum of conversation request time for a task run, excluding fixture resets, dependency setup and judging. Token totals include returned usage from dependency warm-up/priming; task judging is included in token-cost estimates when a calibrated judge was used. Missing usage allowances are separate in the cost ledger.

| Target | Category | Mean assistant latency (s) | Mean returned tokens/run | Mean token cost/run (USD) |
|---|---|---:|---:|---:|
| DeepSeek-V3.1 | aggregation | 2.208 | 7457.9 | 0.003229 |
| DeepSeek-V3.1 | clarification | 2.444 | 5383.9 | 0.002831 |
| DeepSeek-V3.1 | cross_tenant | 2.666 | 4397.3 | 0.002440 |
| DeepSeek-V3.1 | degraded_dependency | 0.790 | 9219.6 | 0.005284 |
| DeepSeek-V3.1 | empty | 2.701 | 7546.6 | 0.004056 |
| DeepSeek-V3.1 | lookup | 2.451 | 5797.7 | 0.002976 |
| DeepSeek-V3.1 | multi_step | 4.134 | 12625.5 | 0.006669 |
| DeepSeek-V3.1 | multi_turn | 5.789 | 17650.6 | 0.009874 |
| DeepSeek-V3.1 | prompt_injection | 2.044 | 4686.7 | 0.002425 |
| DeepSeek-V3.1 | write_action | 2.682 | 7454.0 | 0.004008 |
| gpt-oss-120b | aggregation | 2.753 | 2400.6 | 0.000498 |
| gpt-oss-120b | clarification | 5.290 | 6113.1 | 0.001213 |
| gpt-oss-120b | cross_tenant | 1.252 | 1621.2 | 0.000326 |
| gpt-oss-120b | degraded_dependency | 0.875 | 5621.1 | 0.001050 |
| gpt-oss-120b | empty | 3.452 | 3729.3 | 0.000753 |
| gpt-oss-120b | lookup | 2.936 | 3009.7 | 0.000600 |
| gpt-oss-120b | multi_step | 9.492 | 8131.4 | 0.001739 |
| gpt-oss-120b | multi_turn | 8.927 | 11866.8 | 0.002287 |
| gpt-oss-120b | prompt_injection | 3.017 | 2958.7 | 0.000587 |
| gpt-oss-120b | write_action | 5.110 | 3964.8 | 0.000869 |

## Known checker issue: scores remain frozen

One gpt-oss Houston run is marked as a fabricated vessel because the checker reads nine digits inside a returned 64-character event ID as an MMSI. The complete numeric occurrence is inside that returned identifier, not a vessel claim. This proven parsing case is recorded in known-checker-issue.json. The published score remains frozen; no new checker revision or human label was invented. All 240 new manual-review entries are PENDING in the ignored round-3/manual-review.json template.

## Deployment failure and correction

The first deployment used an image containing an older JAR despite passing startup checks. Live results revealed missing UTC fields and the old outage behavior. The image JAR hash differed from the current verified artifact. The run was stopped; 98 interrupted-prefix rows and six old-image pilot rows remain unchanged and are excluded from comparative results. Their returned usage is included in the ledger; an interrupted call without a complete receipt has a $3 allowance. Fresh ARM images started locally, matched the verified JAR hashes, were pushed under a new tag and redeployed within the 20-minute repair window. Corrected pilots both passed 3/3 and live results confirmed UTC fields. A new artifact-hash guard and three regression checks reject stale images.

The original AWS login also expired during partial provisioning before any host existed. After the operator renewed it, the partial fleet was destroyed and audited before recreation. Neither interruption reset the original three-hour clock. Calibration is a fixed answer set independent of the deployed product; the completed exact-judge/fixture/rubric evidence was reused without additional paid calibration calls.

## Cleanup and verification

All six host archives were copied. Terraform destroyed all 18 resources; both destroy and audit exited 0. The state is empty. The tagging index retains historical instance records, while direct active-service checks are empty:

```json
{
  "activeInstances": [],
  "vpcs": [],
  "volumes": [],
  "snapshots": [],
  "repositories": [],
  "roles": [],
  "instanceProfiles": [],
  "securityGroups": [],
  "subnets": [],
  "keyPairs": [],
  "elasticIps": []
}
```

First apply: 2026-10-10T03:58:13.238400+00:00; cleanup complete: 2026-10-10T06:50:07.114557+00:00; elapsed 171.90 minutes, below the unchanged 180-minute limit. Known launch-time/returned-token estimate $2.043674, plus $3 unknown-use reserve and $0.01 unmeasured ECR allowance = $5.053674 protected gross estimate, below $12. Billing invoice and credit coverage are unverified. No fleet remains running.

Final Maven verify: 149 tests, zero failures/errors/skips. Offline evaluator checks: 83 passed; deployment/benchmark checks after the image guard: 30 passed. The two independent review files retain their original hashes; sample-1 is unchanged. Checker solved-label agreement remains 240/240 in sample, zero false passes/fails; this is not a held-out accuracy estimate. Original scores and version-1 task data remain intact.

## Interpretation and limits

Detected explicit UTC errors fell from 14 to 0 for DeepSeek and 18 to 0 for gpt-oss. Outage completion moved from 8/9 to 9/9 and 5/9 to 9/9 respectively; one Chinese outage task now has an explicit radius, so matched-version comparisons are also reported. Draft completion moved from 8/9 to 9/9 and 5/9 to 9/9. gpt-oss improved overall, while DeepSeek declined; the category table preserves both. These combined changes and stochastic model runs are not an ablation that establishes which individual change caused each score difference. The known event-ID false failure remains in the frozen score. The original generated human-review templates remain pending; the independent review addendum below records the new labels. No usable different-provider judge was available for the gpt-oss target. Historical capability-gap flags no longer veto answers that contain the required facts; they are metadata rather than evidence of a current missing tool.

## Uniform approved-checker application

The live runner still applied the previous strict-identifier veto after the reviewed checker returned solved. That made three correct DeepSeek refusals fail solely because they echoed an identifier supplied by the user. The already-approved distinction between strict echoes and actual data disclosure was applied offline uniformly to both complete matrices. Original runner scores are preserved: DeepSeek 106/120 and gpt-oss 113/120. Revised fact-based scores are 109/120 and 113/120, with pass³ 35/40 each. Tests verify that an echoed refusal can pass while actual foreign tool results still fail. This is the same reviewed checker, not an additional fix for the newly found event-ID issue; that issue remains failed and flagged. No model calls were repeated for regrading.

## Independent review addendum — frozen checker

The answer checker remains `review-v2`; no rule or score has been overwritten. Round-2 240/240 agreement was **in-sample**, because those labels guided its fixes. Round 3 is an **out-of-sample check**: all 18 failures and 53 randomly sampled successes were reviewed. No false passes occurred in that sample, and 10 of the 18 failures were false. The review-corrected estimates assume unreviewed successes are correct; they do not prove zero false passes outside the sample.

| Round | Model | Original checker / runner | Current frozen checker | Review-corrected solved | Method |
|---|---|---:|---:|---:|---|
| 1 | DeepSeek-V3.1 | 57/120 | 73/120 | Unavailable | Offline regrade only; no equivalent independent review |
| 1 | gpt-oss-120b | 13/19 | 15/19 | Unavailable | Incomplete 19-run prefix; offline regrade only |
| 2 | DeepSeek-V3.1 | 59/120 | 113/120 (94.2%) | 113/120 (94.2%) | All 120 answers independently reviewed |
| 2 | gpt-oss-120b | 45/120 | 98/120 (81.7%) | 98/120 (81.7%) | All 120 answers independently reviewed |
| 3 | DeepSeek-V3.1 | 106/120 | 109/120 (90.8%) | 114/120 (95.0%), estimate | All 11 failures reviewed; 0 false passes in 28 sampled successes |
| 3 | gpt-oss-120b | 113/120 | 113/120 (94.2%) | 118/120 (98.3%), estimate | All 7 failures reviewed; 0 false passes in 25 sampled successes |

Original scores and raw results are retained; this ledger does not overwrite any run. Round 1 gpt-oss is incomplete and is not a full model comparison. Round 2 uses task version 1 and round 3 uses version 2: the Chinese outage question gained a radius, and the foreign-port reference was corrected. The original report's matched 38-task comparison remains the appropriate way to isolate these two task differences. Review-corrected figures here use each round's own recorded task version, not a rerun.

Round 2's **240/240 agreement was in-sample**: those same labels guided six checker fixes. It is regression evidence that the identified defects were addressed, not a held-out accuracy estimate. Round 2's full independent review found 113 and 98 solved answers; fully accurate counts were only 91 and 76, respectively. Solved and fully accurate must not be conflated.

Round 3 was not used to tune the checker. Review covered every checker failure (18 total) and a seeded random sample of 53 checker successes (28 DeepSeek, 25 gpt-oss; seed 20261010). There were **0 false passes in the sampled successes**, so all failures were reviewed to estimate corrected totals. **10 of 18 checker failures were false.** DeepSeek gains five solved answers and gpt-oss gains five. Unreviewed successes are assumed correct; this sample supports but does not prove that assumption. These estimates are not a full 240-answer accuracy review or proof of zero false passes in all successes.


### Round-3 category corrections

The original category table above remains the frozen-checker result. The following table adds the independent review corrections; no claim about unsampled successes being fully accurate is made.

| Category | DeepSeek checker → review-corrected | gpt-oss checker → review-corrected |
|---|---:|---:|
| aggregation | 16/18 → 16/18 | 18/18 → 18/18 |
| clarification | 9/9 → 9/9 | 8/9 → 8/9 |
| cross_tenant | 6/6 → 6/6 | 6/6 → 6/6 |
| degraded_dependency | 9/9 → 9/9 | 9/9 → 9/9 |
| empty | 12/12 → 12/12 | 11/12 → 12/12 |
| lookup | 17/18 → 18/18 | 18/18 → 18/18 |
| multi_step | 12/18 → 15/18 | 14/18 → 18/18 |
| multi_turn | 10/12 → 11/12 | 12/12 → 12/12 |
| prompt_injection | 9/9 → 9/9 | 8/9 → 8/9 |
| write_action | 9/9 → 9/9 | 9/9 → 9/9 |

Review-corrected totals: DeepSeek **114/120 (95.0%)** and gpt-oss **118/120 (98.3%)**. The four unresolved parser limitations are correctly labelled extra vessels, digit substrings inside event IDs, emphasized counts, and negation in unrelated clauses. They remain frozen and failed in the automated scores. The sample found two residual timestamp/event-attribution errors despite zero detections from the limited UTC diagnostic.

See [freeze, full per-round score ledger and review method](checker-freeze-round3.md) and the unchanged [independent review](../review/round3-review.md). Query transparency was implemented later and has not been evaluated in these model runs. This documentation update used no AWS calls or model inference.
