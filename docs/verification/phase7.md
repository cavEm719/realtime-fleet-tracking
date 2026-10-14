# Phase 7 local preparation checks

Operators will deploy a fleet across separate AWS hosts, replay the NOAA data,
observe live vessel positions, and evaluate company-scoped answers with Bedrock.
This evidence describes local implementation checks only. No AWS resources were
created; no apply, destroy, image push, deployed benchmark or real model call ran.

Original Desktop checkout, Homebrew JDK 21 and OrbStack, 2026-10-08:

- Resume correction: full `mvn -B -ntp verify` passed 131 tests at 17:17:42 EDT;
  committed separately as d6e8910.
- Final Phase 7 command: `mvn -B -ntp verify`, BUILD SUCCESS at 17:37:23 EDT,
  elapsed 1 min 8 sec. 135 tests: 90 unit, 45 integration, no failures/errors/skips.
- Mocked Bedrock Converse adapter returns tool calls/usage; copilot selects its
  tool generation even when an empty generation comes first. Evaluation mode
  records arguments/results and accumulated tokens; ordinary replies omit evidence.
- Terraform 1.14.0: provider initialization with backend disabled, `fmt -check`,
  and `validate` passed. No plan/apply or authenticated AWS API call was performed.
- Seven offline Python checks passed (six AWS tooling checks plus the existing
  metrics regression): weighted histogram merge, missing samples, chrony state/
  offset parsing, argument values, pending manual review, shell syntax of mocked
  deployment commands/state exclusion, and the existing metric label parser.
- Python compilation and shell `bash -n` passed for the new tooling.
- EC2 cost assumptions were refreshed using the public AWS pricing feed. The
  fourteen-host default is estimated at $1.3533/hour including default gp3/IPv4,
  excluding Bedrock/ECR/transfer/taxes. This is not observed spend.

AWS image architecture, cloud bootstrap/permissions, performance, processor
scale-out conclusions, Bedrock model quality and actual spend remain unverified
until the operator runs the runbook. README tables explicitly say Not run.
