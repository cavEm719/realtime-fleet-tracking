# Gate 3 execution sequence

The evaluation checks whether the copilot can answer questions about synthetic vessel locations and port arrivals. Local rehearsal validates command construction before paid resources exist. Mocked Terraform plans cannot verify live AWS eligibility or capacity; refreshed live plans are a separate read-only preflight.

All commands below run from the original project checkout. Real inventory/plan files and raw replies stay ignored. `TAG` refers to the already built and locally started ARM images. Password/key paths refer to local files; their contents are never logged or copied to the runner. The operator AWS wrapper removes static-key variables and uses only the existing default login session.

```bash
TAG=gate3-retry-1
RESULTS=bench/results/AWS/gate3-private/retry-2
infra/aws/scripts/rehearse-evaluation.sh bench/results/AWS/gate3-private/retry-1 "$RESULTS" "$TAG"
python3 -m unittest discover -s bench -p 'test_*.py'
python3 -m unittest discover -s copilot-eval -p 'test_*.py'

# Read-only preflight: identity, empty running fleet, operator /32, both model availabilities.
# The authorized wrapper captures private outputs instead of printing them.
terraform -chdir=infra/aws plan -target=aws_ecr_repository.service -out="$PWD/$RESULTS/ecr.plan"
terraform -chdir=infra/aws show -json "$PWD/$RESULTS/ecr.plan" > "$RESULTS/ecr-live-plan.json"
# Inspect: exactly two repositories, additions only; start three-hour clock/watchdog.
terraform -chdir=infra/aws apply "$PWD/$RESULTS/ecr.plan"
python3 infra/aws/scripts/repository-inventory.py --output infra/aws/repositories.local.json
infra/aws/scripts/push-images.sh infra/aws/repositories.local.json "$TAG"

terraform -chdir=infra/aws plan -out="$PWD/$RESULTS/fleet.plan"
terraform -chdir=infra/aws show -json "$PWD/$RESULTS/fleet.plan" > "$RESULTS/fleet-live-plan.json"
# Inspect: sixteen additions only, six evaluation hosts; exact two Bedrock ARNs.
terraform -chdir=infra/aws apply "$PWD/$RESULTS/fleet.plan"
terraform -chdir=infra/aws output -json inventory > infra/aws/inventory.json
python3 infra/aws/scripts/deploy.py --inventory infra/aws/inventory.json --key ~/.ssh/fleet-bench \
  --tag "$TAG" --password-file /tmp/fleet-smoke-db-password --model deepseek.v3-v1:0 \
  --bedrock-region us-west-2 --evaluation --fixed-now 2024-01-08T12:00:00Z
python3 infra/aws/scripts/seed-eval.py --inventory infra/aws/inventory.json --key ~/.ssh/fleet-bench --reset-demo
# A workstation SSH tunnel uses localhost:18083 -> copilot localhost:8083.
```

Both judge calibration commands run before either pilot. The evaluator runs on the workstation; judge requests execute through SSH on the runner using its instance role. The single strict-schema verdict tool is forced for both models. Untrusted judges contribute no verdicts; deterministic metrics remain available. `--max-cost-usd` guards each output run while the watchdog also tracks cumulative tokens and host time against $12.

```bash
python3 copilot-eval/bedrock.py --url http://127.0.0.1:18083 \
  --inventory infra/aws/inventory.json --key ~/.ssh/fleet-bench \
  --model-manifest copilot-eval/models-cross.json --model deepseek.v3-v1:0 --model-role target \
  --judge-model openai.gpt-oss-120b-1:0 --judge-role judge --region us-east-1 \
  --prices 0.58 1.68 0.15 0.60 --calibration-only --allow-untrusted-judge \
  --max-cost-usd 2 --output "$RESULTS/calibration-gpt"
python3 copilot-eval/bedrock.py --url http://127.0.0.1:18083 \
  --inventory infra/aws/inventory.json --key ~/.ssh/fleet-bench \
  --model-manifest copilot-eval/models-cross.json --model openai.gpt-oss-120b-1:0 --model-role comparison \
  --judge-model deepseek.v3-v1:0 --judge-role comparisonJudge --region us-west-2 \
  --prices 0.15 0.60 0.58 1.68 --calibration-only --allow-untrusted-judge \
  --max-cost-usd 2 --output "$RESULTS/calibration-deepseek"
```

Pilot/full invocations reuse the corresponding calibration by `--calibration-evidence`. Pilot arguments are `--purpose pilot --repeats 1 --task-ids atlas-status la-yesterday draft-la`; full arguments are `--purpose full --repeats 3`. DeepSeek runs first, then only the copilot container switches to gpt-oss in us-east-1 using the same verified image; the API and fixture stay unchanged. Fresh conversations and per-task reseeding are enforced by the evaluator. Each pilot/full output is distinct; comparison includes the different-judge caveat and pending manual review.

A repository-only failure permits fixes and continuation. With EC2 present, an ordinary failure starts a twenty-minute repair deadline; unsuccessful repair triggers copying and destruction. Security/IAM/billing/unexpected-resource failures stop immediately. The three-hour deadline and $12 estimate override repair time. Successful completion also copies every output, destroys the whole Terraform state and runs `infra/aws/scripts/remaining-resources.sh us-east-1`. No fleet remains paused.


Exact pilot/full commands (the evaluator's exit 1 means measured task failures, not an infrastructure failure; retain and report them):

```bash
evaluate_deepseek() {
  python3 copilot-eval/bedrock.py --url http://127.0.0.1:18083 \
    --inventory infra/aws/inventory.json --key ~/.ssh/fleet-bench \
    --model-manifest copilot-eval/models-cross.json --model deepseek.v3-v1:0 --model-role target \
    --judge-model openai.gpt-oss-120b-1:0 --judge-role judge --region us-east-1 \
    --prices 0.58 1.68 0.15 0.60 --allow-untrusted-judge --max-cost-usd 4 \
    --calibration-evidence "$RESULTS/calibration-gpt" "$@"
}
evaluate_gpt() {
  python3 copilot-eval/bedrock.py --url http://127.0.0.1:18083 \
    --inventory infra/aws/inventory.json --key ~/.ssh/fleet-bench \
    --model-manifest copilot-eval/models-cross.json --model openai.gpt-oss-120b-1:0 --model-role comparison \
    --judge-model deepseek.v3-v1:0 --judge-role comparisonJudge --region us-west-2 \
    --prices 0.15 0.60 0.58 1.68 --allow-untrusted-judge --max-cost-usd 4 \
    --calibration-evidence "$RESULTS/calibration-deepseek" "$@"
}
evaluate_deepseek --purpose pilot --repeats 1 --task-ids atlas-status la-yesterday draft-la --output "$RESULTS/pilot-deepseek"
python3 infra/aws/scripts/switch-copilot.py --inventory infra/aws/inventory.json --key ~/.ssh/fleet-bench --tag "$TAG" --model openai.gpt-oss-120b-1:0 --region us-east-1
evaluate_gpt --purpose pilot --repeats 1 --task-ids atlas-status la-yesterday draft-la --output "$RESULTS/pilot-gpt"
# Check actual pilot tokens and cumulative cost + remaining host reserve before continuing.
python3 infra/aws/scripts/switch-copilot.py --inventory infra/aws/inventory.json --key ~/.ssh/fleet-bench --tag "$TAG" --model deepseek.v3-v1:0 --region us-west-2
evaluate_deepseek --purpose full --repeats 3 --output "$RESULTS/full-deepseek"
python3 infra/aws/scripts/switch-copilot.py --inventory infra/aws/inventory.json --key ~/.ssh/fleet-bench --tag "$TAG" --model openai.gpt-oss-120b-1:0 --region us-east-1
evaluate_gpt --purpose full --repeats 3 --output "$RESULTS/full-gpt"
# Copy host output archives; raw evaluations already live on the workstation.
terraform -chdir=infra/aws destroy -auto-approve
infra/aws/scripts/remaining-resources.sh us-east-1
```
