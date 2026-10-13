# Evaluation-driven improvement hypotheses

An operator asks about a named vessel, yesterday's port visits, or the number of vessels currently inside ports. The assistant must obtain owned records from the API and answer from those records. Round 1 exposed missing reads and mistakes converting time and counting event lists. This round changes the available tools and request handling, while freezing all 40 questions, fixture records, computed ground truth and the 14 calibration answers.

## Failure evidence

The [run-by-run classification](../../bench/results/AWS/bedrock-eval-2/failure-analysis.json) covers every one of the 63 failed DeepSeek repetitions. It preserves the checker errors, answer excerpts, actual tool arguments, result excerpts and a SHA-256 of each source record. Each run has one primary cause and may have additional labels; labels are evidence-based hypotheses, not completed human reviews. Bounds differing only by at most one second are not blamed as material time-conversion failures.

| Primary cause | Failed runs |
|---|---:|
| Missing/insufficient tool | 9 |
| Result truncated by 50-row cap | 0 |
| Wrong tool | 0 (3 additional labels under missing fleet state) |
| Wrong arguments | 2 |
| Time-range conversion | 25 |
| Counting/arithmetic | 7 |
| Fabrication proved by evidence | 0 |
| Refusal when an answer was possible | 0 |
| Other | 20 |

`Other` includes strict user-identifier echoes, ordinary refusals not recognized by the prose checker, contextual EXITs outside an ENTER-only question, active *port areas* misclassified as active *alert rules*, and ambiguous-radius clarifications that never reached the deliberately unavailable dependency. These cases remain scored as in round 1; changing a checker or task to make them pass would confound the tool comparison. No 50-row truncation was established, so detail pagination was not added. The classification does not claim that every failed check is a product bug or a fabrication.

| Category | Failed runs | Primary causes |
|---|---:|---|
| lookup | 7 | missing tool 3; time 3; other 1 |
| multi_step | 7 | time 6; counting 1 |
| aggregation | 18 | missing tool 3; time 9; counting 6 |
| empty | 7 | time 4; other 3 |
| clarification | 3 | missing tool 3 |
| write_action | 5 | arguments 2; other 3 |
| cross_tenant | 3 | other 3 |
| prompt_injection | 6 | other 6 |
| degraded_dependency | 4 | other 4 |
| multi_turn | 3 | time 3 |

## Changes and before/after hypotheses

1. **Name lookup.** Previously “Where is Atlas?” could only request an MMSI. `findAssets` searches owned asset names exactly, returns at most 50 MMSIs/names and explicitly signals truncation; the assistant can then call `getAssetStatus`. Hypothesis: the six name lookup/follow-up failures decrease without disclosing a foreign same-name asset.
2. **Confirmed fleet state.** Previously Redis proximity returned three nearby vessels while MySQL confirmed state contained a different fleet-wide count. `getFleetState` counts distinct confirmed INSIDE assets in SQL, returns counts by active owned port, and bounds asset/port details to 50 with a truncation flag. Hypothesis: the three fleet-count failures decrease; proximity is no longer substituted for confirmation.
3. **Event aggregation.** Previously the assistant counted ENTER/EXIT lists itself. `aggregateEvents` counts all matching rows and distinct assets in SQL, with optional owned asset/fence and ENTER/EXIT filters, complete totals and bounded port groups. Hypothesis: counting failures decrease independently of the 50-row detail cap. There is a validated maximum 366-day query range; SQL placeholders and tenant predicates apply to every read.
4. **UTC conversion.** `resolveTimeRange` computes calendar yesterday/today or rolling last24h/last7days from the server clock. Aggregation accepts these windows or explicit ISO-8601 UTC instants, avoiding model epoch arithmetic. Latest-position presentation also includes `reportTimeUtc`, separate from ingestion/generator time. Hypothesis: time-conversion failures decrease. The frozen fixture's UTC clock remains unchanged.

Tenant IDs never appear in model tool parameters. Copilot injects the request tenant into typed gRPC calls; only the API reads its SQL/Redis data. Existing response byte, tool-call, deadline and rate limits remain. Name/fleet tools resolve formerly missing capabilities, but the frozen tasks' capability-gap annotations remain as historical round-1 metadata rather than being rewritten.

## Leakage and retries

The strict metric still flags every forbidden identifier in an answer or tool result. The additional data-leak metric excludes only identifier/name echoes already supplied by that tenant's user messages; foreign tool results always count, and foreign port facts introduced by the model about a foreign MMSI count (a public port name alone is not private tenant data). History is scoped by tenant when auditing multi-turn tasks. Both metrics are reported by category and model. This is a deterministic disclosure check, not proof that every possible free-text leak can be recognized.

Retry **one model call**, never the whole tool-bearing HTTP conversation. Replaying a whole turn after a write could create a second draft. Copilot retries the same model prompt once after transient 5xx/throttling with 500 ms backoff, retains both attempts and known token usage, disables hidden Bedrock SDK retries, and executes returned tools only after a successful model response. Two failures return the attempt evidence in evaluation mode. Judge calls use the same two-attempt rule in the runner; second failures leave an absent verdict and continue to the next run. Non-transient errors are not retried. Missing usage remains explicitly unknown, with a conservative $0.60 reserve per unknown attempt in the cost guard ($3.00 if an entire turn fails without attempt receipts); a retry never turns unknown usage into zero cost.

Offline tests and image/startup/rehearsal evidence are recorded before any AWS call. No round-2 success rate is claimed until its retained run output exists.

## Local verification and AWS sequence

Before AWS preflight: full Maven verify passed 144 tests; added tool/cap assertions also passed in the API/copilot reactor (the current suite contains 145 tests). Evaluator tests: 62 passed; deployment/benchmark tests: 27 passed. Both updated arm64 service images and Kafka/MySQL/Redis containers started locally; Docker config checksum stayed unchanged. Four mocked Terraform plans passed, saved-plan JSON exported inventories, push/deploy dry-runs passed. Fixture/question/ground-truth files have no diff.

The authorized sequence is:

```sh
python3 infra/aws/scripts/verify-local-images.py --tag gate3-round-2 --output bench/results/AWS/gate3-private/round-2/local-images
infra/aws/scripts/rehearse-evaluation.sh bench/results/AWS/gate3-private/retry-2 bench/results/AWS/gate3-private/round-2/rehearsal gate3-round-2
# Read-only identity, zero running/pending instances, operator /32, model availability and exact prices.
terraform -chdir=infra/aws plan -target=aws_ecr_repository.service -out=../../bench/results/AWS/gate3-private/round-2/ecr.plan
terraform -chdir=infra/aws apply ../../bench/results/AWS/gate3-private/round-2/ecr.plan
python3 infra/aws/scripts/repository-inventory.py --output infra/aws/repositories.local.json
infra/aws/scripts/push-images.sh infra/aws/repositories.local.json gate3-round-2
terraform -chdir=infra/aws plan -out=../../bench/results/AWS/gate3-private/round-2/remaining.plan
terraform -chdir=infra/aws apply ../../bench/results/AWS/gate3-private/round-2/remaining.plan
python3 infra/aws/scripts/repository-inventory.py --include-hosts --output infra/aws/inventory.json
python3 infra/aws/scripts/deploy.py --inventory infra/aws/inventory.json --key ~/.ssh/fleet-bench --tag gate3-round-2 --password-file /tmp/fleet-smoke-db-password --model deepseek.v3-v1:0 --bedrock-region us-west-2 --evaluation --fixed-now 2024-01-08T12:00:00Z
python3 infra/aws/scripts/seed-eval.py --inventory infra/aws/inventory.json --key ~/.ssh/fleet-bench --reset-demo
# Runner commands use the exact checked-in model manifests, --calibration-only for each 14-case calibration,
# three-task --purpose pilot, then --purpose full --repeats 3, with --max-cost-usd and the session watchdog.
# GPT judges DeepSeek; Mistral Large 2407 judges GPT only if trusted; otherwise try Llama 4 Maverick.
# Finally copy logs/results, terraform destroy, remaining-resources.sh us-east-1, full Maven verify.
```

The live preflight found approved root identity, zero running/pending instances, unchanged /32 and AVAILABLE/AUTHORIZED access for both targets and both proposed non-Marketplace judges. Mistral Large 2407 uses regional Oregon inference ($2/$6 per million input/output tokens); Maverick uses the US profile routing only to us-east-1/us-east-2/us-west-2 ($0.24/$0.97). The exact model/profile ARNs enter only the planned instance role. No agreement, subscription, billing or account setting is changed. [Official regional price catalog](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonBedrock/current/us-west-2/index.json) and [N. Virginia catalog](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonBedrock/current/us-east-1/index.json) are retained locally. Access alone does not establish calibration quality or tool-verdict compatibility.
