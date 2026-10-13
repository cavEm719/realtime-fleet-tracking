# Frozen answer checker and independent review

Operators need a fleet answer they can use, not merely a tool call that succeeded. The answer checker tests required facts in retained answers against fixture-derived references. Independent review can identify answers the checker rejects despite solving the task. We report those two measures separately so a parser defect is not presented as a model failure.

## Score ledger: original values retained

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

## Four known remaining false-fail patterns

1. **Correct extra vessels:** explicitly labelled departures or already-inside vessels are mistaken for non-matching arrivals. Five runs: DeepSeek `ny-chinese` trials 1–3 and `corrected-chinese` trial 2; gpt-oss `la-today-empty` trial 1.
2. **Digits inside event IDs:** a numeric substring in a SHA-256 event hash is mistaken for a fabricated MMSI. One run: gpt-oss `houston-week` trial 1.
3. **Emphasized counts:** the checker misses a valid count in wording such as `Distinct vessels: **2**`. Three runs: gpt-oss `savannah-week` trials 1–3.
4. **Unrelated negative clauses:** a correct INSIDE statement about Los Angeles is contradicted by a separate clause saying the vessel is not inside other ports. One run: DeepSeek `name-lookup` trial 3.

These are known limitations, not implemented fixes. The answer checker is frozen at `review-v2`, SHA-256 `9b7de1ac3e15c32cc97f853f51713ea90148e6ae168ec70266558fc9762d9d41`. The machine-readable freeze record is [checker-freeze.json](../../copilot-eval/checker-freeze.json). An offline test checks that the checker and review files retain their hashes and that recorded sample/failure counts agree with the unchanged labels. Any future authorized change needs a new checker version and validation on new, unreused answers; it must preserve this frozen version and the original scores.

## Genuine failures and residual accuracy problems

Six DeepSeek failures remain: four incomplete vessel-identification answers and two empty provider-error replies. Two gpt-oss failures remain: one tool-cap run without an answer and one request for coordinates instead of resolving the Seattle port. The sample also found a DeepSeek answer rounding 11:55 to 12:00 and a gpt-oss answer associating a 22:00 EXIT with ENTER. The offline explicit-UTC diagnostic reported zero in round 3 but does not cover all timestamp precision or event attribution; review demonstrates that boundary. No claim of perfect timestamp accuracy is made.

The newly implemented query transparency renders executed conditions and result tables from code. It has local unit/integration evidence and a separate task suite, but was implemented after the retained round-3 runs: no model score in this ledger measures that feature.

## Evidence status

The round-3 review Markdown and JSON were committed byte-for-byte unchanged. The original generated manual-review templates remain PENDING; the independently supplied labels are a separate recorded review, not fabricated template completion. `sample-1.md` remains unchanged. Raw runner/regrade outputs, costs, judge calibration records and cleanup evidence are unchanged. This update performs no AWS commands, model inference or fresh answer regrading.

Sources: [round-2 full review](../review/round2-review.md), [round-3 independent review](../review/round3-review.md), [round-3 labels](../review/round3-independent-review.json), [original revised-checker ledger](copilot-checker-review-v2.md), and [round-3 retained comparison](../../bench/results/AWS/bedrock-round-3/comparison.json).

Local verification for this update: 92 offline evaluator tests passed, including hash-freeze, review-count and score-ledger checks. Category correction sums equal 114/120 and 118/120. Public safety was checked before each commit. Java/application code was unchanged; no Maven rebuild or AWS/model execution was needed for this reporting update.
