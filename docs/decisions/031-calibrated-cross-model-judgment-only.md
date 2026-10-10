# ADR 031: Calibrated cross-model judgment only

Status: accepted in the implemented experiment. History: `2c2753b / 4e3e08c`.

## Context

A judge can accept fabrication or favor its own output.

## Decision

Forced verdict tool, critical leak/fabrication calibration and a different target/judge; reject failing or unavailable judges.

## Consequences

GPT target has no usable different-provider judge in later rounds; deterministic metrics remain.

Implementation, tests and limits: [design](../design/evaluation.md).
