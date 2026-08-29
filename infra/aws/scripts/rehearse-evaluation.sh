#!/usr/bin/env bash
# Offline only: mocked Terraform plans and exports from retained plan JSON.
set -euo pipefail
plans=${1:?directory containing retained ecr-plan.json and full-plan.json}
results=${2:?ignored rehearsal output directory}
tag=${3:?verified local image tag}
mkdir -p "$results"
terraform -chdir=infra/aws test -filter=tests/profiles.tftest.hcl > "$results/terraform-mock-rehearsal.log" 2>&1
python3 infra/aws/scripts/repository-inventory.py --plan-json "$plans/ecr-plan.json" --output "$results/repositories-planned.local.json"
python3 infra/aws/scripts/repository-inventory.py --plan-json "$plans/full-plan.json" --include-hosts --output "$results/inventory-planned.local.json"
infra/aws/scripts/push-images.sh --dry-run "$results/repositories-planned.local.json" "$tag" > "$results/push-dry-run.log"
python3 infra/aws/scripts/deploy.py --dry-run --inventory "$results/inventory-planned.local.json" \
  --key ~/.ssh/fleet-bench --tag "$tag" --password-file /tmp/fleet-smoke-db-password \
  --model deepseek.v3-v1:0 --bedrock-region us-west-2 --evaluation \
  --fixed-now 2024-01-08T12:00:00Z > "$results/deploy-dry-run.log"
