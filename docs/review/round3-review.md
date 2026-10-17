# Round 3 independent review (out-of-sample checker check)

The checker was revised against the round-2 review, so its 240/240 agreement there is in-sample. Round 3 answers were never used to tune it. This review checks it on that new data, using the same rubric as [round2-review.md](round2-review.md). Labels are in [round3-independent-review.json](round3-independent-review.json).

## What was reviewed

- **Every run the checker failed**: 11 DeepSeek-V3.1 and 7 gpt-oss-120b runs.
- **A random sample of runs the checker passed** (seed 20261010): 28 DeepSeek and 25 gpt-oss runs.

## Results

| Model | Checker solved | Checker failures that were actually solved | False passes in sampled successes | Review-corrected solved |
| --- | ---: | ---: | ---: | ---: |
| DeepSeek-V3.1 | 109/120 | 5 of 11 | 0 of 28 | 114/120 (95.0%) |
| gpt-oss-120b | 113/120 | 5 of 7 | 0 of 25 | 118/120 (98.3%) |

The checker again passed no wrong answer. Because it has no observed false passes, reviewing only its failures gives the corrected totals above. That total assumes the unsampled successes are also correct, which the 53-run sample supports but does not prove.

Out of sample, 10 of 18 checker failures were false. The in-sample 100% agreement therefore overstated its accuracy. The remaining false-fail patterns:

1. **Correctly labelled extra vessels.** Answers that also list departures, or note a vessel already inside, fail "non-matching vessel" (DeepSeek ny-chinese ×3 and corrected-chinese t2, gpt-oss la-today-empty t1).
2. **Digits inside event IDs.** A run of digits within an event hash is read as a fabricated MMSI (gpt-oss houston-week t1).
3. **Counts in emphasized phrases.** "Distinct vessels: **2** (MMSIs …)" is not parsed as the count (gpt-oss savannah-week ×3).
4. **Contradiction on unrelated clauses (still present).** "INSIDE the Port of Los Angeles … not inside any other monitored ports" fails the `inside` fact (DeepSeek name-lookup t3).

## Genuine failures

- DeepSeek answered the Savannah question with the count but without the requested MMSIs (3 runs), and gave "1 vessel entered" without naming it once (corrected-chinese t3).
- Empty replies after provider errors: DeepSeek inside-fleet ×2.
- gpt-oss reached the tool-call cap without an answer once (ambiguous-recent t2), and once asked for coordinates instead of resolving the Seattle fence (data-injection-nearby t1).

## Timestamp accuracy after the fix

In the reviewed runs, converted times were correct except two: one DeepSeek reply rounded an 11:55 report to 12:00, and one gpt-oss reply labelled a 22:00 EXIT as an ENTER. Round 2 had about one wrong time in five runs.

## Recommendation

Freeze the checker here and report results as checker score plus review-corrected score. Further tuning against the same reviewed runs would repeat the in-sample problem; any further change must be validated on runs that were not used to make it.
