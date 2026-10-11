# Round 3: checker, product fixes and completed AWS evaluation

Operators need correct vessel and port answers, clear outage replies, and accurate UTC times. This round validated the checker against the independent review, fixed the product presentation and outage handling, and completed both 40-task × 3 AWS matrices. The full report includes category comparisons, the 38 unchanged tasks, detected timestamp errors, calibration, latency, costs, setup failures and mandatory cleanup.

See [the completed results](aws-copilot-round3-results.md) and [checker validation](copilot-checker-review-v2.md). Original round-1/2 scores and task version 1 are preserved. The review files were committed unchanged and sample-1 was left untouched. All new human reviews remain PENDING. One newly discovered event-ID parsing false failure remains in the frozen score and is explicitly flagged.

The login interruption and stale-image attempt are retained as failures, not substituted for results. Both corrected matrices completed; all 18 resources were destroyed within the original three-hour clock. Active-service audit lists and Terraform state are empty. The protected gross estimate including reserves is $5.053674, below the $12 limit; invoice and credit coverage are unverified.

The uniform offline application of the approved checker yields DeepSeek 109/120 and gpt-oss 113/120; original runner grades 106/120 and 113/120 remain preserved. Both pass³ totals are 35/40. The event-ID parsing issue remains unfixed. Evaluator checks after the runner alignment: 83 passed.
