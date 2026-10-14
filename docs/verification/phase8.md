# Phase 8 documentation verification

The platform accepts vessel reports, keeps current locations, confirms port movements and answers bounded fleet questions. This documentation pass explains those flows, why the mechanisms exist, how failures recover and what the retained evidence can establish. It does not execute cloud operations, inference or benchmarks.

## Delivered

- Architecture: normal position/event flow, service separation, data ownership, synchronous/asynchronous boundaries and trace scope.
- Twelve design topics with the nine specified sections and ten interview Q&As each.
- Thirty-six short decision records, plus a chronological journal with the complete commit index retained at the start of documentation.
- A concise README front page and local runbook. The long previous README is relocated to legacy-project-reference.md with relative links adjusted; its historical phase results and original scores remain visible.
- Private bilingual interview preparation is excluded by interview/ in .gitignore and absent from the tracked file list. Private content is not a public project artifact.

## Offline verification

Verified on 2026-10-10 in the original project checkout:

| Check | Result |
|---|---|
| scripts/check-documentation.py | PASS: 54 architecture/design/decision/journal/runbook/reference files; 12 exact section lists; 10 Q&As/topic; existing local links/source-line bounds/named tests |
| Offline evaluator unittest discovery | 92 tests passed; no model calls |
| Public-safety unittest discovery | 6 tests passed |
| Frozen evidence | Checker and supplied round-3 labels retain expected hashes; no evaluation tasks, fixtures, labels, raw scores or result files changed |
| Private folder | Git ignore confirmed; no interview files tracked |
| Numeric provenance spot checks | Five AWS latency scenario p99 medians/min/max and four laptop cases match retained raw trial/client outputs; profile sample weights and round-3 returned token/cost summaries match reports |
| Commit publication safety | scripts/check-public-safety.sh run before every documentation commit |

The application code is unchanged. Its most recent retained full Maven checkpoint is 163 passing tests on JDK 21.0.12.1 with local Kafka/MySQL/Redis integration containers, recorded in copilot-query-transparency.md. No new Maven build is presented as having run for this documentation pass.

## Evidence sources and interpretation

| Claim family | Repository evidence | Interpretation retained |
|---|---|---|
| Laptop before/after and slow clients | bench/results/Phase61/summary.json, per-trial clients.json/manifests/metric deltas, setup/machine.json | Matched workload medians/ranges; forced collection included in GC time; baseline 10,000 heap failure excluded from completed metrics; laptop slow p99 worsened. |
| Original profiling | bench/results/profile/before-snapshots/profile-summary.json and profile/after/profile-summary.json | Inclusive JFR samples and estimated allocation weights; failures truncate windows, so no percentage capacity improvement follows. |
| AWS throughput, latency and resources | bench/results/AWS/gate2-summary.json; resource/member-lag/Redis CSV; raw per-trial pipeline.json, clocks and resource logs | Separate-host API limit, gateway scale-out; clock snapshots/missing scrapes/counter resets remain explicit. |
| Evaluation counts and review method | checker-freeze-round3.md; checker-freeze.json; unchanged independent round-2/3 labels; retained regrades/comparison.json | Round-2 agreement is in-sample; round-3 corrected scores assume uninspected successes are correct; original values retained. |
| Model usage/cost | bedrock-round-2/summary.json, retained round-3 target summaries, AWS cost ledger | Returned tokens at historical approved prices; unknown usage/hosts separate, not invoice or credit coverage. |
| Implementation correction | Git history, current Java/Lua/XML/proto and named tests | Distinguish caught design gaps and injected faults from observed cloud/test failures; no production incidents asserted. |

All newly summarized results derive from existing evidence. Detailed phase tables and raw retained results remain the source for historical values; documentation is not a new measurement campaign.

## Corrected historical wording and unverifiable claims

- The first committed ingestion script already combined Redis effects; the separate dedupe/pipeline crash gap was caught before that implementation, not a verified committed failure.
- The refreshed Phase 6.1 5,000-client baseline completed. Its 10,000-client baseline exhausted heap; earlier Phase 6 company-wide failures are separately retained.
- Seeding every pre-cursor overlap row was a rejected proposal, not an observed committed version. Only tenant-validated client-confirmed IDs seed the implementation.
- Java 21 carrier starvation from synchronized model I/O was a design risk; no measured starvation event is claimed.
- Creation-time/ID is a visibility-oriented resume cursor with finite overlap, not a true transaction commit sequence. Delays outside the window remain a limit.
- The precise cause of the old 1,000-client AWS smoke p99 is unresolved; no retained per-stage profile supports blaming sustained two-core gateway saturation.
- Reduced geofence flapping is not labeled accuracy. Round-3 zero explicit-UTC detections are not perfect timestamp correctness; independent inspection found rounding/association problems.
- Corrected round-3 model totals are estimates, not a full inspection of all checker successes. Query transparency has local verification and an unrun separate model suite.
- Actual AWS invoices/credit coverage, continuous clock accuracy, production capacity/security/HA and fresh account resource/model-access state are unverified. Historical cleanup evidence remains labelled as historical; no fresh AWS calls were made.
