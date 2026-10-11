# Bedrock evaluation model update

The evaluation asks realistic fleet-operator questions against the same fixed company data. The primary assistant now uses AWS-managed DeepSeek-V3.1; a Claude assistant answers the same questions for comparison. Code checks the facts and tenant isolation. A separate Claude judge checks unsupported prose and clarification quality only after passing the 14-case calibration. Human review remains pending. No model quality or usage result is claimed here.

## Official model and pricing evidence

| Role | Invocation ID | Bedrock client region | Standard input/output USD per million tokens |
| --- | --- | --- | --- |
| Primary | `deepseek.v3-v1:0` | us-west-2 | 0.58 / 1.68 |
| Comparison | `us.anthropic.claude-haiku-4-5-20251001-v1:0` | us-east-1 | 1.10 / 5.50 |
| Judge | `us.anthropic.claude-sonnet-4-6` | us-east-1 | 3.30 / 16.50 |
| Comparison fallback, only if Haiku unavailable | `us.anthropic.claude-sonnet-4-5-20250929-v1:0` | us-east-1 | 3.30 / 16.50 |

DeepSeek is the managed foundation model, not a separately hosted marketplace endpoint. Its [AWS model card](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-deepseek-deepseek-v3-1.html) documents Converse and client-side tool use. The [AWS region matrix](https://docs.aws.amazon.com/bedrock/latest/userguide/models-region-compatibility.html) lists US Ohio and Oregon, with no Geo/global inference profile. The model card also includes Virginia: these documents disagree, so the plan selects Oregon rather than assuming Virginia support. The [launch announcement](https://aws.amazon.com/about-aws/whats-new/2025/09/deepseek-v3-1-model-fully-managed-amazon-bedrock/) confirms the original Oregon availability. Live account availability still needs verification.

The [Haiku card](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-haiku-4-5.html) and [Sonnet judge card](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-sonnet-4-6.html) document Converse/tool support. US inference-profile destinations must be refreshed with GetInferenceProfile from the selected source region before granting IAM permissions. A US prefix alone is not a verified destination list.

Rates were checked against the [AWS pricing page](https://aws.amazon.com/bedrock/pricing/) and public AWS Price List files, selecting Standard on-demand regional/Geo inference, without batch, caching, flex or global-discount rates:

- [AmazonBedrock Oregon price list](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonBedrock/current/us-west-2/index.json), publication 2026-10-06T14:47:26Z: DeepSeek input SKU `Z2K8WK6696RJH4Y5`, output `JJ7XDCN9CKHV75P8`, USD 0.00058/0.00168 per 1,000 tokens.
- [Foundation Models Virginia price list](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonBedrockFoundationModels/current/us-east-1/index.json), publication 2026-10-08T23:56:37Z: Haiku SKUs `JQDUC8Q4K8C6GSGH` / `X629GDA2GXAP6R54`, Sonnet 4.6 `KPFGPZJ8VWMREGAV` / `GMB9AQ5DYNAGYZEC`, Sonnet 4.5 fallback `SSK4DT4JBRUQJYP5` / `3YN5KGC2HJU9VC5A`; original unit one million tokens.

These public price checks do not establish account access, agreement status or credit coverage. Those are pending the user's account-upgrade confirmation and live read-only preflight. No AWS account or inference command ran during this update.

## Permissions, pilot gate and reporting

The prepared local Terraform allowlist contains five exact ARNs: DeepSeek's Oregon foundation model, the Sonnet 4.6 Virginia profile and its three previously verified US destination foundation models. No wildcard is added and no IAM change has been applied. Haiku permissions wait for actual profile-destination verification. Real account values remain in ignored local files; the public manifest uses placeholders. All created resources remain us-east-1, while only inference may use the approved US regions.

Each target first answers exactly three tasks: vessel status (`atlas-status`), yesterday's Los Angeles arrivals (`la-yesterday`), and a draft rule (`draft-la`). Each pilot independently calibrates all 14 judge cases, then runs those three tasks once and stops. Report actual returned tokens, estimated token cost and calibration evidence, then wait for user go after the billing-console check. A missed leak or fabrication invalidates the judge and stops task execution. Gross costs count toward $25 even if credits cover the bill.

Full runs use identical 40 questions, fixture, pinned UTC clock and judge, normally three repetitions each. A budget-constrained one-repetition Claude comparison reports pass³ as unmeasured. The comparator rejects different task wording, fixture, clock or judge. It reports success, pass³, leaks, latency, tokens and cost by category, while leaving manual review pending. The Claude comparison shares a model family with the judge; that limitation remains even though the DeepSeek primary does not.

## Offline verification and planning cost

45 evaluation tests, 7 benchmark-tool tests and 6 public-safety tests passed: **58 tests**, without API keys or AWS calls. Tests cover regional selections and prices, pilot size/repetition limits, one-repetition reporting, comparison consistency and deployed-region validation. No Java source changed; Maven was not rerun for this tooling-only update. The prior 137-test Maven verification remains historical evidence, not a new execution.

Assumptions: 9,000 target input / 1,400 output tokens per task run, 2,000 judge input / 200 output, independently repeated calibration in both pilots and both full suites, plus outage warm-up/probes. Two pilots estimate $0.412116; both three-repetition full suites plus pilots $6.560760; reducing Claude to one repetition $4.142960; doubled-token sensitivity $13.121520. Host costs are additional. These are planning estimates, not measured usage, invoices or ceilings. Actual pilots determine the next budget decision. Reprice the Sonnet fallback if required.

The fleet remains at the last recorded destroyed state. After upgrade confirmation, refresh identity, running-instance inventory, public IP, 25/0/0 smoke plan and model access/agreements, report the results, and wait for go before apply. The five-hour clock starts at apply, not during these local preparations.
