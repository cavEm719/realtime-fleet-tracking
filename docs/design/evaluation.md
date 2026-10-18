# Testing whether fleet answers solve the task

## What: the problem in plain words, with a concrete example

An operator needs a usable answer: the correct vessel, port, count and time, or a clear statement that data is unavailable. A successful tool call can still yield a wrong answer. Evaluation therefore runs realistic questions against deterministic fleet data, checks required/forbidden facts and stored rule effects, and keeps the answer checker separate from model-quality judgment.

## Why: requirements and constraints that drove the design

Mocked models prove tool/control-flow contracts but cannot establish real tool choice, arithmetic, conversion, clarification or resistance to instructions embedded in data. A synthetic fixed fixture enables exact code-derived truth and safe foreign-company canaries. Small task coverage, stochastic model behavior and imperfect grading require repeated runs and independent checks.

## How: the design, with pointers to key code (file:line), SQL, Lua, proto

**Current round (v3, round 4).** After the Python rewrite ([ADR 045](../decisions/045-local-deepseek-evaluation.md)) the suite runs locally: `run_local.py` drives the compose stack (API, MySQL primary and replica, Redis, two copilot instances) and DeepSeek's API with model `deepseek-flash`. Before every task repetition it reseeds the fixture on the primary and waits until the replica has applied it. Task set v3 (41 tasks) drops the three alert-draft tasks, turns the two circuit-open tasks into plain API outages (the copilot no longer has a breaker on its own API), and adds two follow-ups whose second turn goes to the other copilot instance and two questions asked while the replica applier is stopped until its data is more than 60 seconds old. Grading uses the same frozen checker; every checker failure is reviewed and successes are sampled. A token-based guard stops the run at a cost limit. Results: [round 4 review](../review/round4-review.md) — checker 102/123, review-corrected 122/123 (estimate), no real cross-company disclosure, 2.10 CNY.

The rest of this document describes rounds 1–3 on AWS Bedrock with the Java copilot.

The unchanged fixture contains 25 vessels across three tenants, eight port fences and 100 events over seven days, with inside/at-sea/near-outside latest positions and foreign canary names. COPILOT_FIXED_NOW pins 2024-01-08T12:00:00Z; system prompts include that UTC clock. Ground truth is computed from fixture rows, never by a model. Forty tasks cover lookup, multistep/aggregation, empty, clarification, drafts, cross-tenant/injection, degraded dependency, multi-turn and Chinese. Each complete target matrix has three fresh-conversation repetitions per task.

The deterministic checker inspects required facts, forbidden facts, tool-result leakage and MySQL rule side effects. Strict leak counts include foreign identifiers echoed from the question; data-leak counts exclude those supplied identifiers but still reject foreign tool/model data. Writes must create one correct DRAFT and never ACTIVE. Tool and argument correctness remain diagnostics, not the headline. pass³ counts tasks whose three repetitions all pass.

Judges use a forced submit_verdict tool with a validated strict schema for free-text faithfulness/clarification. Fourteen handwritten good/bad cases calibrate each judge; any missed leak/fabrication disqualifies its verdicts. gpt-oss judges DeepSeek. DeepSeek's original 13/14 missed fabrication and was rejected. Mistral Large and Llama Maverick requests were rejected before producing judgments for the gpt-oss target: accuracy is unavailable, not 0%. No model judges itself.

[fixture.py:1](../../copilot-eval/fixture.py#L1); [graders.py:1](../../copilot-eval/graders.py#L1); [bedrock.py:1](../../copilot-eval/bedrock.py#L1); [checker-freeze.json:1](../../copilot-eval/checker-freeze.json#L1).

| Round | Model | Original checker / runner | Current frozen checker | Review-corrected solved | Method |
|---|---|---:|---:|---:|---|
| 1 | DeepSeek-V3.1 | 57/120 | 73/120 | Unavailable | Offline regrade only; no equivalent independent review |
| 1 | gpt-oss-120b | 13/19 | 15/19 | Unavailable | Incomplete 19-run prefix; offline regrade only |
| 2 | DeepSeek-V3.1 | 59/120 | 113/120 (94.2%) | 113/120 (94.2%) | All 120 answers independently reviewed |
| 2 | gpt-oss-120b | 45/120 | 98/120 (81.7%) | 98/120 (81.7%) | All 120 answers independently reviewed |
| 3 | DeepSeek-V3.1 | 106/120 | 109/120 (90.8%) | 114/120 (95.0%), estimate | All 11 failures reviewed; 0 false passes in 28 sampled successes |
| 3 | gpt-oss-120b | 113/120 | 113/120 (94.2%) | 118/120 (98.3%), estimate | All 7 failures reviewed; 0 false passes in 25 sampled successes |

Round-3 corrected values assume unreviewed checker successes are correct; they are estimates, not full accuracy labels. Round 1 gpt-oss stopped after 19 runs and cannot be compared as a full 120-run matrix.

## Alternatives considered and trade-offs

Tool-selection pass rates are easy but inadequate: the model can call the right tool and misstate results. Model judges handle prose but need calibration and remain supplemental. Code gives deterministic reference counts and strict isolation checks, while independent answer inspection detects parser false fails. Freezing rather than continually tuning on the latest answers prevents each test set becoming training data for the checker.

## Failure modes and how each is handled

Transient target/judge 5xx or throttling gets one backoff retry; attempts/usage are retained. A second failure marks that run failed and continues. Unknown paid usage receives conservative reserve rather than zero cost. Provider errors stay in denominators. Capability gaps are recorded rather than silently rewriting difficult questions. Two task-definition corrections create version 2 (outage-Chinese radius and foreign-port reference), with old version results retained and a matched 38-task subset for comparisons.

## Problems encountered and how they were solved

056d506 classifies round-1 failures. a3514a6 adds SQL event counts, fleet-confirmed summary, name lookup and safe retries where evidence supported them. The detail tool's 50-row cap could not answer full-fleet counts, so aggregation moved into SQL. 2c19ef8 fixes six false-fail patterns supported by the full independent round-2 inspection; 240/240 agreement is in-sample because those labels drove the fixes. c6abbc3 returns matching ISO UTC strings, stops outage loops and explicitly states stored-only draft semantics. Round 3 is new output, not checker tuning.

d8e41c8 freezes review-v2 after round-3 inspection: all 18 checker failures plus 53 seeded sampled successes were inspected; zero false passes in the sample, 10 false fails among 18 failures. Four known patterns remain: correctly labelled extra vessels, digits inside event hashes, emphasized counts and unrelated negative clauses. They are documented, not fixed. Detected explicit UTC errors fell 14→0 for DeepSeek and 18→0 for gpt-oss, but inspection still found rounding/event attribution mistakes; the diagnostic is incomplete.

## Evidence: tests by name, measured numbers with conditions

[Frozen ledger and limits](../verification/checker-freeze-round3.md), [full round-2 labels](../review/round2-independent-review.json), [round-3 labels](../review/round3-independent-review.json), [round-3 report](../verification/aws-copilot-round3-results.md), [comparison JSON](../../bench/results/AWS/bedrock-round-3/comparison.json). Full round-2 solved counts are 113/120 and 98/120, but fully accurate counts are 91/120 and 76/120. Round 3 code-checked data leaks are 0/120 each; strict counts DeepSeek 3, gpt-oss 0. This is not a global security guarantee.

Round-3 retained runs: DeepSeek target input/output 748,403/30,702 tokens; gpt-oss judge 210,897/45,305; returned-token combined task cost $0.544471. gpt-oss target 538,117/70,431, no usable task judge, cost $0.122976. Mean request latency 2.898/4.649 seconds respectively, excluding reset/judging. Approved historical prices per million input/output: DeepSeek $0.58/$1.68, gpt-oss $0.15/$0.60. No current price lookup is claimed. Calibration/pilots/hosts/reserves are separate: round-3 known estimate $2.043674 + $3 unknown reserve + $0.01 ECR allowance = $5.053674 protected estimate, not invoice/credit coverage.

Frozen checker categories (R2 task v1, R3 task v2; solved/attempted and pass³):

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

Round-3 corrected category estimates, applying the same sampled-success assumption as the total:

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

**Returned task-token estimates across retained rounds (historical prices, excluding standalone calibration/hosts and unknown-use reserves):**

| Round / target | Attempted task runs | Target input / output | Task judge input / output | Known token estimate USD |
|---|---:|---:|---:|---:|
| round1 / DeepSeek-V3.1 | 120 | 432,271 / 34,179 | 225,270 / 55,933 | 0.375488 |
| round1 / gpt-oss-120b | 19 | 31,278 / 8,229 | 0 / 0 | 0.009629 |
| round2 / DeepSeek-V3.1 | 120 | 730,676 / 31,773 | 202,338 / 43,232 | 0.533461 |
| round2 / gpt-oss-120b | 120 | 454,997 / 64,136 | 0 / 0 | 0.106731 |
| round3 / DeepSeek-V3.1 | 120 | 748,403 / 30,702 | 210,897 / 45,305 | 0.544471 |
| round3 / gpt-oss-120b | 120 | 538,117 / 70,431 | 0 / 0 | 0.122976 |

Round-1 GPT is a partial prefix; costs include only returned usage, not an assumed full run. [Round-2 retained cost/usage summary](../../bench/results/AWS/bedrock-round-2/summary.json) preserves the original run scores alongside usage; the frozen score ledger above remains authoritative for regrades.

Tests include test_checker_freeze.py, grader/calibration fake-judge tests and retained offline regrades. The separate eight-task query-transparency-v1 suite checks basis/actual-call consistency; only offline fake-transport tests ran, no live model results. Its rules do not alter the frozen checker.

## Known limits and what production would need

Round-2 in-sample agreement is not held-out accuracy. Round 3 samples successes rather than inspecting all; corrected totals depend on an explicit assumption. Ten of 18 false fails prove remaining checker limitations. Small synthetic data/questions do not establish production usefulness or safety. Rounds contain combined changes and stochastic variation, not causal ablation. Generated manual-review templates remain PENDING; supplied independent labels are separate preserved evidence. No frozen tasks/labels/results are changed by documentation.

## Likely interview questions with short, precise answers

**Q: Why not just mocked tests?**

A: They do not measure real model reasoning or answers.

**Q: Who computes truth?**

A: Fixture-processing code, not a model.

**Q: What is the headline?**

A: Task success and data leaks; tool choice is diagnostic.

**Q: Why three repetitions?**

A: Show consistency through pass³ as well as average success.

**Q: Why two leak counts?**

A: Strict echoes and newly disclosed foreign data are different failure meanings.

**Q: How do writes get checked?**

A: Inspect MySQL for one tenant-correct DRAFT and no activation.

**Q: Why cross-judge?**

A: Avoid self-grading; only calibrated different-model judgments are used.

**Q: What did 240/240 prove?**

A: In-sample regression agreement, not general checker accuracy.

**Q: What did round 3 reveal?**

A: Ten of eighteen checker failures were false despite frozen rules.

**Q: Did ISO strings eliminate all timestamp mistakes?**

A: No; the explicit conversion detector missed rounding and event association errors.
