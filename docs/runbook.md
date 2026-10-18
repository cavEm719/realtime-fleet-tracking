# Local runbook

A local replay sends vessel observations to the API. Kafka retains them, a processor updates current positions and port state, and a gateway delivers subscribed updates. The Python copilot is optional; without `DEEPSEEK_API_KEY` its questions return 503. Nothing here calls a model unless a key is configured.

## Build and verify

Use JDK 21, Maven, Docker with Compose v2, curl, Python 3 and [uv](https://docs.astral.sh/uv/). From the repository root:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
docker compose up -d --wait
mvn -B -ntp verify
(cd copilot && uv sync && uv run python gen_proto.py && uv run pytest)
```

On Homebrew if necessary, select `$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home` as JAVA_HOME. Integration tests create their own disposable containers and do not change Compose data. Missing Docker fails container tests rather than silently skipping them. `mvn test` is not a substitute for `verify` integration coverage. `gen_proto.py` generates the Python gRPC stubs from the shared proto; they are not committed.

`docker compose up` starts Kafka, Redis, the MySQL primary and its read replica. The replica copies the primary's GTID position on first start and then receives migrations and data by replication; copilot reads use its SELECT-only `fleet_ai` account.

## Start applications and replay

Run each command in its own terminal, all from the repository root. API startup runs Flyway migrations; wait for its health before starting the processor, which loads fences before consuming:

```bash
java -jar api/target/api-0.1.0-SNAPSHOT-exec.jar --fleet.ai-read.url=jdbc:mysql://localhost:3307/fleet --fleet.ai-read.username=fleet_ai --fleet.ai-read.password=fleet_ai
java -jar processor/target/processor-0.1.0-SNAPSHOT-exec.jar
java -jar gateway/target/gateway-0.1.0-SNAPSHOT-exec.jar
(cd copilot && uv run uvicorn fleet_copilot.app:app --port 8083)
```

Default application ports are API 8080, processor 8081, gateway 8082, copilot 8083; internal API gRPC is 9095. Without the `fleet.ai-read` settings the API reads copilot queries from the primary through a read-only pool. The copilot reads `DEEPSEEK_API_KEY`, `COPILOT_MODEL` (default `deepseek-flash`), `API_GRPC_TARGET` and `REDIS_HOST` from the environment.

```bash
java -jar replayer/target/replayer-0.1.0-SNAPSHOT-exec.jar replayer/src/test/resources/tiny-ais.csv 60 200
curl -f http://localhost:8080/actuator/health
curl -f http://localhost:8081/actuator/prometheus
```

The small seeded-port track is `replayer/src/test/resources/los-angeles-geofence.csv`. Replay into existing data intentionally ignores old/equal position timestamps. These commands do not reset demo data.

## Two instances of each service

After the Maven build:

```bash
docker compose --profile multi up -d --build --wait
```

The multi profile includes the API, two copilots (8083, 8084), two processors, two gateways, nginx and infrastructure. Put `DEEPSEEK_API_KEY=...` in a `.env` file in the repository root (ignored by git) for the copilots. Nginx forwards WebSocket upgrade headers and distributes connections. Do not simultaneously bind the same host ports with host-run apps. [Resume design](design/resume.md) is the authoritative cursor explanation.

## Evaluation

`copilot-eval/run_local.py` runs the task suite against a compose project started with `COPILOT_FIXED_NOW=2024-01-08T12:00:00Z COPILOT_EVALUATION_ENABLED=true` and calls the paid DeepSeek API; see [evaluation](design/evaluation.md).

## Stop and evidence

`docker compose stop` preserves local volumes. Avoid deleting volumes unless intentionally resetting isolated test data. Benchmark tooling has separate named projects/reset rules; use its manifests and [performance design](design/performance.md) rather than resetting the ordinary demo. Run `scripts/check-public-safety.sh` before commits.
