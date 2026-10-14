# Phase 6.2 verification

Vessels may cross port boundaries while an operator is disconnected. Phase 6.2
adds durable fence-event catch-up on reconnect, then returns the connection to
live delivery. It also limits each gateway's Redis traffic to companies with
local connections.

Verified in the original Desktop checkout on 2026-10-08 with Homebrew JDK 21
and OrbStack. No benchmarks were rerun.

- Command: `mvn -B -ntp verify`.
- Result: BUILD SUCCESS at 15:53:58 America/New_York, elapsed 1 min 6 sec.
- 128 tests: 84 unit and 44 integration; zero failures, errors or skips.
- Resume tests cover a new connection replaying missed events, exclusive cursors,
  live arrivals during catch-up, sorted history/live merge and dedupe, oversized
  gaps without partial replay, and rejection of subscription changes during replay.
- In-process gRPC tests cover bounded pagination, oversized-gap detection, and
  deadline expiry. MySQL-backed API tests cover tenant and asset isolation,
  timestamp/event-ID tie ordering, last-event-ID resolution, and pagination.
- Tenant lifecycle tests cover first/last connection behavior, a delayed last-close
  racing a new connection, and dropping unrelated Kafka events before recipient lookup.
- Actual Redis/Kafka/two-gateway tests retain broadcast coverage and prove that
  tenant-2 publishes have zero Redis subscribers when only tenant-1 connections
  exist, and that closing the last connection removes tenant-1's subscription.

Resume defaults: at most 1000 historical events, at most 1000 buffered live
arrivals, and a five-second overall API deadline. Exceeding a bound or failing
history retrieval sends `resync_required`; a fresh snapshot and connection are
required. Positions use only the latest-value path, without historical replay.
