# Reproducible experiments on separate hosts

## What: the problem in plain words, with a concrete example

A single laptop shares CPU and memory between services and generators, making scale conclusions uncertain. The AWS deployment puts broker, data stores, applications and generators on separate ARM hosts, collects their resource/clock evidence and then destroys the experiment. A smaller profile runs only copilot evaluation dependencies.

## Why: requirements and constraints that drove the design

The fleet is a bounded-cost experiment, not production hosting. Resources live in us-east-1, carry project=fleet-bench and expose operator ports only to a current /32. Model calls may use explicitly approved US regions. ECR holds ARM service images. A hard runtime deadline, cost ledger, artifact copying, Terraform destroy and remaining-resource audit accompany paid runs.

## How: the design, with pointers to key code (file:line), SQL, Lua, proto

Terraform defines a VPC, one public subnet/AZ, scoped security groups, Graviton instance types/counts, ECR repositories, one planned instance role/profile with ECR pull and exact Bedrock invocation ARNs, and inventory outputs. Benchmark profile selects processors/gateways/nginx/generators. Evaluation profile selects only Kafka, Redis, MySQL, API, copilot and runner.

Local rehearsal plans targeted steps, exports planned repository/inventory data and dry-runs push/deploy scripts. Verified linux/arm64 images are built and started before cloud provisioning; ECR-only apply precedes push, then additions-only host apply. Ephemeral pull authentication uses the instance role; operator login is not copied into host files. Artifact/JAR hashes verify that live images contain the locally verified build. Trial manifests record data, clock offsets, tracing disabled and resource caps.

[main.tf:1](../../infra/aws/main.tf#L1); [variables.tf:1](../../infra/aws/variables.tf#L1); [push-images.sh:1](../../infra/aws/scripts/push-images.sh#L1); [deploy.py:1](../../infra/aws/scripts/deploy.py#L1); [remaining-resources.sh:1](../../infra/aws/scripts/remaining-resources.sh#L1). The [AWS runbook](../../infra/aws/README.md) provides exact deployment/cleanup commands; this document makes no AWS calls.

## Alternatives considered and trade-offs

Graviton keeps ARM builds consistent with the laptop and reduces experiment cost; another architecture would need verified alternate images. Separate hosts help attribution, while a single AZ/single data-store instance deliberately sacrifices HA. ECR-only provisioning avoids paying for hosts before images exist. Managed Kafka/RDS/Redis would improve operations but change this controlled experiment and its budget.

## Failure modes and how each is handled

Only planned/tagged resources may be changed. Security/IAM surprises stop execution. Session expiry requires renewed operator login, never access-key creation or another credential source. After hosts exist, a repair window is bounded; unresolved failures copy evidence and destroy. IP changes update only scoped ingress. Cleanup audits direct active resources because the tag index can retain already-deleted records. Cost estimates use launch times, historical prices and conservative unknown inference reserves, not assumed credit coverage.

## Problems encountered and how they were solved

185fa60 records free-plan launch restrictions and verified cleanup; paid-plan readiness precedes later runs. aa57800 records Claude Marketplace payment errors; 13d9122 moves to DeepSeek/gpt-oss managed endpoints without a new Marketplace subscription. 818653b uses the bundled Amazon Linux CLI; 554302d includes executable benchmark JARs; 21a9351 fixes MySQL readiness shell expansion; 553c51d honors the configured consumer group.

b3248ae fixes temporary Docker configuration hiding normal plugin lookup; 2c2753b/2b9f7d5 require ARM startup/rehearsal. fde7bab exports ECR-only state without uncreated-host outputs. b13f829 records expired provisioning session and cleanup after renewal. c3f901e rejects images containing an older JAR by hash after round-3 live fields exposed the mismatch. These are retained experimental failures, not production incidents.

## Evidence: tests by name, measured numbers with conditions

[Gate-1 smoke](../verification/aws-gate1-smoke.md), [Gate-2 matrix](../verification/aws-gate2-matrix.md), [retry/rehearsal](../verification/gate3-retry-commands.md), [round-3 deployment/cleanup](../verification/aws-copilot-round3-results.md) and bench tooling unit tests provide evidence. Round-3 historical cleanup copied six host archives, destroyed 18 resources and completed in 171.90 minutes under a 180-minute session deadline. Direct active-service audit was empty while tag records retained history. This is the recorded cleanup result, not a fresh live account check. Benchmarks and model evaluations have separate manifests/results; costs are not invoices.

## Known limits and what production would need

One AZ, public host addresses, one broker and single stores lack production failover. Internal gRPC service authentication is out of scope; production needs mTLS or signed service identity. The experiment role allows exact planned model resources only, not arbitrary IAM/account changes. This phase performs no cloud operations or fresh model price/access verification.

## Likely interview questions with short, precise answers

**Q: Why AWS after laptop?**

A: Separate host samples distinguish shared-machine effects from service limits.

**Q: Why one AZ?**

A: Keep the experiment small; it does not demonstrate HA.

**Q: Where are resources created?**

A: us-east-1; model inference may use approved US endpoints separately.

**Q: Why exact Bedrock ARNs?**

A: Limit invocation permission to approved model/profile resources.

**Q: Why build ARM images first?**

A: Avoid paying for hosts while discovering local build/start failures.

**Q: Why ECR-only first?**

A: Verified images can be pushed before provisioning compute.

**Q: What did Docker config break?**

A: An empty temporary config hid normal buildx plugin lookup.

**Q: How was stale deployment found?**

A: Missing expected live fields led to comparing packaged and image JAR hashes.

**Q: What happens at expiry?**

A: Stop credential operations, require renewed login and complete cleanup with that session.

**Q: How do you know cleanup happened?**

A: Retained destroy/state output and direct remaining-resource audit, not tag disappearance alone.
