# Public repository file check

The benchmark still needs accurate measurements, but published evidence does not need an operator's cloud account, workstation paths or full environment listings. Tracked text now uses `<ACCOUNT_ID>`, `<PROJECT_ROOT>` and `<LOCAL_PATH>` placeholders. Measurement values are unchanged. Unsanitized copies and binary recordings remain in ignored local files.

The evaluation's committed model manifest is a public template. The actual model manifest is `infra/aws/evaluation-models.local.json`, ignored by Git; the evaluation runbook uses this local input. The existing operator policy and exact model choices are unchanged. Local results containing real identifiers must be sanitized before staging.

`scripts/check-public-safety.sh` checks both the staged index and tracked working copies, including binary contents and symlink targets. It rejects account-number ARN fields, account-ID JSON fields, the operator's recorded public addresses, home paths, assistant metadata and full environment dumps. Diagnostics name only the file and violation category, never the matched value. Scoped reads of a single approved configuration setting are allowed; evaluation validation now reads only three named non-secret values.

Operator addresses come from ignored `scripts/.public-safety-local.json` (`public_ips` array), ignored smoke tfvars, or `FLEET_PUBLIC_IP`. Missing address configuration fails closed. Update that local list when the operator's address changes; preserve previous addresses there too so older evidence is still checked. This is a targeted publication check, not a general secret scanner.

A tracked `.githooks/pre-commit` runs the check. It is enabled for this checkout with `git config core.hooksPath .githooks`; enable the same local setting on a new clone. Run the script before every commit. Six isolated offline tests exercise clean placeholders, all rejected categories, staged/working-copy differences, binary data, missing operator configuration and selective setting reads.

Sanitation removes identifiers from the current tracked files; it does not rewrite prior Git history. No AWS resource or model command was executed for this change.
