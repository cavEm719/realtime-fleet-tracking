# ADR 007: Spatial candidates and hysteresis

Status: accepted in the implemented experiment. History: `2635476 / e2296d6`; superseded by ADR 038 (grid).

## Context

GPS/AIS boundary jitter can produce false activity and checking every fence per point wastes work.

## Decision

Grid candidates plus exact distance, inner/outer radii, consecutive points and source-time duration; retain naive replay mode.

## Consequences

Confirmation is delayed; circles and unlabeled flaps are not accuracy evidence.

Implementation, tests and limits: [design](../design/geofence.md).
