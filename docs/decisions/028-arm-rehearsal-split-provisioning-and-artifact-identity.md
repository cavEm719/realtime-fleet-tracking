# ADR 028: ARM rehearsal, split provisioning and artifact identity

Status: accepted in the implemented experiment. History: `2c2753b / 2b9f7d5 / c3f901e`.

## Context

Live script/build/image errors waste a paid fleet and can evaluate obsolete code.

## Decision

Build/start locally, dry-run exports/push/deploy, ECR first, then hosts, verify packaged/image JAR hashes.

## Consequences

Requires state/output discipline and bounded repair time; plans and artifacts remain recorded.

Implementation, tests and limits: [design](../design/aws.md).
