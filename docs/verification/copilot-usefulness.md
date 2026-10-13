# Copilot usefulness evaluation: local verification

The operator suite checks whether the assistant answers company-scoped operational questions correctly and actually stores requested drafts. It no longer treats tool selection as task completion.

## Scope and evidence

- Deterministic fixture: 25 vessels, three tenants, eight ports, 100 crossings across seven days, 68 confirmed-state rows and latest positions. Fixed UTC clock: 2024-01-08T12:00:00Z.
- 40 operator tasks, planned three repetitions each. Three unsupported name/inventory tasks remain explicit capability gaps, counted as unsuccessful in the overall denominator.
- Code-derived reference facts, answer/tool-result leak checks, MySQL before/after rule checks, real outage/circuit audit hooks, 14 judge calibration cases, per-task/category reports and PENDING manual-review templates.
- Offline Python: 34 evaluation tests plus seven existing benchmark tests passed. Includes fake-judge calibration, count/time normalization, foreign canaries, failed/extra/ACTIVE writes, data-injection exposure, API restoration and retained leak evidence on later-turn failure.
- Full `mvn -B -ntp verify`: BUILD SUCCESS; 137 tests (92 unit, 45 integration), zero failures/errors/skips; 1:07 elapsed; finished 2026-10-08T23:30:40-04:00. Java 21, local Docker/Testcontainers.
- Python compilation and `git diff --check` passed. Raw Maven output remains local in /tmp, not committed.

## Unmeasured results and cost

No AWS command, real target/judge model call, rich-fixture database deployment or benchmark was performed for this local preparation. Real task success, real judge accuracy, latency and billed tokens are therefore unmeasured. Fake-judge tests are verification of code, not evidence of model quality. Human review remains PENDING.

Approved Sonnet 4.5 target and Sonnet 4.6 judge prices are $3.30 input / $16.50 output per million tokens. Planning estimate for 120 task runs, task judging, 14 calibration calls and outage warm-up/probes: **$8.6427**. Doubling all assumed token quantities: **$17.2854**. Neither is a measured bill or hard ceiling. Gate 3 must check the remaining whole-phase $25 allowance; the evaluation's $12 estimate guard can stop an incomplete matrix.

The evaluation guide documents assumptions, fixture regeneration and future gated execution. Existing laptop benchmark results are unchanged.
