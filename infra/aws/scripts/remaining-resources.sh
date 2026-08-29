#!/usr/bin/env bash
# Read-only operator audit after destroy. Never deletes resources.
set -euo pipefail
region=${1:-us-east-1}
aws resourcegroupstaggingapi get-resources --region "$region" \
  --tag-filters Key=project,Values=fleet-bench --output json
# Include terminated instances and EBS resources (tagging API has coverage limitations).
aws ec2 describe-instances --region "$region" --filters Name=tag:project,Values=fleet-bench \
  --query 'Reservations[].Instances[].{id:InstanceId,state:State.Name,ip:PublicIpAddress}' --output json
aws ec2 describe-volumes --region "$region" --filters Name=tag:project,Values=fleet-bench --output json
aws ec2 describe-snapshots --region "$region" --owner-ids self --filters Name=tag:project,Values=fleet-bench --output json
# ECR/IAM/VPC leftovers appear in the first response; inspect its ARNs.
for role in $(aws iam list-roles --query 'Roles[?starts_with(RoleName, `fleet-bench-`)].RoleName' --output text); do
  aws iam list-role-tags --role-name "$role" --output json
  echo "Candidate remaining role: $role"
done
for profile in $(aws iam list-instance-profiles --query 'InstanceProfiles[?starts_with(InstanceProfileName, `fleet-bench-`)].InstanceProfileName' --output text); do
  aws iam list-instance-profile-tags --instance-profile-name "$profile" --output json
  echo "Candidate remaining instance profile: $profile"
done
