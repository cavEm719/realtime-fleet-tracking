# Gate 1 smoke attempt: blocked and cleaned up

The smoke test needs separate Kafka, Redis, MySQL and application hosts before it can replay vessel reports or connect clients. The approved nine-host fleet could not launch: AWS rejected every instance type as ineligible for Free Tier. A read-only account-plan check returned FREE / ACTIVE. This is an account restriction, not an application failure; no service performance result exists.

Pre-apply checks passed: approved root caller verified, no pending/running instances in us-east-1, operator public address unchanged, fresh plan 25 additions / zero changes / zero deletions. Budget/email creation was superseded by the user; the internal $25 estimate remains enforced. The five-hour clock began at 2026-10-09T04:01:28.170108+00:00.

Terraform created 16 supporting resources but no instance. The mandatory cleanup destroyed all 16. No host results needed copying because no host launched; raw provisioning/failure/destroy/audit output is retained locally under ignored gate1-private evidence. Cleanup was verified at 2026-10-09T04:04:18.228889+00:00, 170.1 seconds after apply began. The deadline watcher is stopped; nothing is left running across the gate wait.

| Smoke activity | Result |
| --- | --- |
| ARM image push / deployment | Not started: no instances |
| Every-service health | Not run |
| Short S1 replay | Not run |
| 1,000-client S2 | Not run |
| Application fixes | None; no account setting or instance-size change attempted |
| EC2/EBS/public IPv4/Bedrock estimated spend | $0.00; no launch timestamps or model calls |

The cost estimate is not an invoice. Credits were not subtracted. The supporting VPC, role/profile, key pair and empty image repositories were all removed; no images/scans ran.

Required remaining-resources script output:

```json
{
    "ResourceTagMappingList": []
}
[]
{
    "Volumes": []
}
{
    "Snapshots": []
}
```

There were no role/profile candidates printed, and Terraform state list was empty. No other account resources were modified.

The approved non-Free-Tier sizes cannot run under the current plan. AWS documents that a Paid plan removes Free-plan offering restrictions; changing plans is a user-only account/billing action. [AWS plan documentation](https://aws.amazon.com/free/free-tier-faqs/). Wait for the user's direction before another apply. A successful smoke test still precedes Gate 2.
