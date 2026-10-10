# ADR 032: Independent inspection and frozen checker

Status: accepted in the implemented experiment. History: `2c19ef8 / d8e41c8`.

## Context

Parser defects made correct answers fail, while fixing them against labels overstates validation.

## Decision

Retain original scores, revise supported round-2 defects, label its agreement in-sample, freeze review-v2 and inspect all round-3 failures plus sampled successes.

## Consequences

Corrected round-3 scores are estimates; four known false-fail patterns remain unfixed.

Implementation, tests and limits: [design](../design/evaluation.md).
