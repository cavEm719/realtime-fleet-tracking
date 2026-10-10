# ADR 039: Accept previous fence geometry for up to a minute

Status: accepted. Supersedes ADR 012.

## Context

The share lock and version rejection prevented a transition confirmed against geometry that had just been edited or deleted. Requirement N5.1 accepts the old geometry for up to one minute, including for a deleted port.

## Decision

Remove the `FOR SHARE` fence read and version rejection from the transition transaction. Change events update caches promptly; the 60-second reconciliation bounds the window.

## Consequences

Within a minute of an edit or delete, a crossing may be recorded against the previous geometry. Events still record the fence version that was evaluated.
