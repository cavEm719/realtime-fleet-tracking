# Phase 5.5 verification (2026-10-08)

- `mvn -B -ntp verify`: 107 tests passed (66 unit, 41 integration); no failures, errors or skips.
- All services in an isolated two-processor/two-gateway Compose profile reached healthy status.
- One WebSocket client per gateway received matching event IDs for the functional EXIT and ENTER checks.
- MySQL recorded ENTER → EXIT → ENTER for the test vessel; all three outbox rows were published.
- Kafka assigned six of the twelve position partitions to each processor.
- The retained trace JSON verifies continuous ingest-to-delivery traces for all three transitions, including both gateways. Separate batch spans link three source records each.
- An earlier Docker connection failure required an authorized OrbStack restart. Smoke checks exposed and fixed the nginx IPv4 health check, shared upstream counts, and scheduled-observation trace override.
- No benchmarks or paid model calls were run. The isolated stack and volumes were removed; original demo/benchmark volumes were unchanged.

Raw process logs are ignored. Phase 6 benchmark raw outputs remain under `bench/results/` as their separate historical baseline.
