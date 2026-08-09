#!/usr/bin/env python3
"""One measured run against ONLY the isolated fleetbench Compose project."""
import argparse
import json
import os
import subprocess
import sys
import time
import threading
import urllib.request
from pathlib import Path
from metrics import snapshot, compare

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path("/opt/homebrew/opt/openjdk@21/bin/java")
COMPOSE = ["docker", "compose", "-p", "fleetbench", "-f", "bench/compose.yaml"]


def command(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, **kwargs)


def start_java(module, output, env, flags=()):
    log = output.open("w")
    process = subprocess.Popen(
        [
            str(JAVA),
            *flags,
            "-jar",
            f"{module}/target/{module}-0.1.0-SNAPSHOT-exec.jar",
        ],
        cwd=ROOT,
        env=env,
        stdout=log,
        stderr=subprocess.STDOUT,
    )
    return process, log


def wait_health(port, process):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Service on {port} exited: {process.returncode}")
        try:
            with urllib.request.urlopen(
                f"http://localhost:{port}/actuator/health", timeout=2
            ) as response:
                if json.load(response)["status"] == "UP":
                    return
        except Exception:
            time.sleep(0.5)
    raise TimeoutError(f"Service on {port} did not become healthy")


def reset_database(folder):
    # Data destruction is deliberately confined to the separate benchmark project.
    command(
        COMPOSE + ["exec", "-T", "redis", "redis-cli", "FLUSHALL"],
        stdout=subprocess.DEVNULL,
    )
    sql = (
        "DELETE FROM alert_rule; DELETE FROM outbox; DELETE FROM geofence_event; "
        "DELETE FROM asset_fence_state; DELETE FROM asset;"
    )
    result = subprocess.run(
        COMPOSE
        + ["exec", "-T", "mysql", "mysql", "-ufleet", "-pfleet", "fleet", "-e", sql],
        cwd=ROOT,
        capture_output=True,
    )
    # First startup has no schema yet; Flyway will create it.
    if (
        result.returncode
        and b"doesn't exist" not in result.stderr
        and b"Unknown database" not in result.stderr
    ):
        raise RuntimeError(result.stderr.decode())
    with (folder / "reset-kafka.log").open("w") as log:
        command(
            [
                str(JAVA),
                "-Xmx256m",
                "-jar",
                "loadtest/target/loadtest-0.1.0-SNAPSHOT-exec.jar",
                "reset-kafka",
                "localhost:19092",
            ],
            stdout=log,
            stderr=subprocess.STDOUT,
        )
    time.sleep(2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario")
    parser.add_argument("--dataset", default="data/west-coast-4h.csv")
    parser.add_argument("--clients", type=int, default=0)
    parser.add_argument("--all", type=int, default=100)
    parser.add_argument("--slow", type=int, default=0)
    parser.add_argument("--seconds", type=int, default=20)
    parser.add_argument("--concurrency", type=int, default=200)
    parser.add_argument("--mode", choices=["speed", "max", "none"], default="max")
    parser.add_argument("--speed", type=float, default=100)
    parser.add_argument("--switch-after", type=float, default=0)
    parser.add_argument("--rate", type=int, default=10000)
    parser.add_argument(
        "--geofence", choices=["naive", "hysteresis"], default="hysteresis"
    )
    parser.add_argument("--jfr", action="store_true")
    args = parser.parse_args()
    folder = ROOT / "bench/results" / args.scenario
    folder.mkdir(parents=True, exist_ok=True)
    manifest = vars(args).copy()
    manifest["startedEpochMs"] = int(time.time() * 1000)
    import hashlib

    manifest["jarSha256"] = {
        module: hashlib.sha256(
            (ROOT / f"{module}/target/{module}-0.1.0-SNAPSHOT-exec.jar").read_bytes()
        ).hexdigest()
        for module in ["api", "processor", "gateway", "loadtest", "replayer"]
    }
    manifest["gatewayMaxConnections"] = 20000
    manifest["tracingEnabled"] = False
    manifest["gitCommit"] = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
    ).strip()
    manifest["jvmFlags"] = {
        "api": ["-Xms128m", "-Xmx512m"],
        "processor": ["-Xms128m", "-Xmx512m"],
        "gateway": ["-Xms128m", "-Xmx1024m"],
        "loadtest": ["-Xms128m", "-Xmx1024m"],
        "replayer": ["-Xms128m", "-Xmx512m"],
    }
    (folder / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    processes, logs = [], []
    status = {"status": "running"}
    try:
        reset_database(folder)
        env = dict(
            os.environ,
            KAFKA_BOOTSTRAP_SERVERS="localhost:19092",
            REDIS_PORT="16379",
            MYSQL_URL="jdbc:mysql://localhost:13306/fleet",
            MYSQL_USER="fleet",
            MYSQL_PASSWORD="fleet",
            API_PORT="18080",
            API_GRPC_PORT="19095",
            TRACING_ENABLED="false",
            PROCESSOR_PORT="18081",
            GATEWAY_PORT="18082",
            SERVER_TOMCAT_MAX_CONNECTIONS="20000",
            GEOFENCE_MODE=args.geofence,
            OPENAI_API_KEY="",
            ANTHROPIC_API_KEY="",
        )
        api_flags = manifest["jvmFlags"]["api"].copy()
        if args.jfr:
            api_flags += [
                f"-XX:StartFlightRecording=settings=profile,filename={folder}/api.jfr,dumponexit=true,maxsize=32m"
            ]
        api, log = start_java("api", folder / "api.log", env, api_flags)
        processes.append(api)
        logs.append(log)
        wait_health(18080, api)
        command(
            COMPOSE
            + [
                "exec",
                "-T",
                "mysql",
                "mysql",
                "-ufleet",
                "-pfleet",
                "fleet",
                "-e",
                f"UPDATE tenant SET ingest_rate_per_sec = {args.rate};",
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        processor_flags = manifest["jvmFlags"]["processor"].copy()
        if args.jfr:
            processor_flags += [
                f"-XX:StartFlightRecording=settings=profile,filename={folder}/processor.jfr,dumponexit=true,maxsize=32m"
            ]
        processor, log = start_java(
            "processor", folder / "processor.log", env, processor_flags
        )
        processes.append(processor)
        logs.append(log)
        wait_health(18081, processor)
        flags = manifest["jvmFlags"]["gateway"].copy()
        if args.jfr:
            flags += [
                f"-XX:StartFlightRecording=settings=profile,filename={folder}/gateway.jfr,dumponexit=true,maxsize=32m"
            ]
        gateway, log = start_java("gateway", folder / "gateway.log", env, flags)
        processes.append(gateway)
        logs.append(log)
        wait_health(18082, gateway)
        time.sleep(2)
        snapshot(folder, "before")
        laglog = (folder / "lag-monitor.log").open("w")
        logs.append(laglog)
        lag = subprocess.Popen(
            [
                str(JAVA),
                "-Xmx256m",
                "-jar",
                "loadtest/target/loadtest-0.1.0-SNAPSHOT-exec.jar",
                "lag",
                "localhost:19092",
                "position-listener",
                "300",
                str(folder / "lag.jsonl"),
            ],
            cwd=ROOT,
            stdout=laglog,
            stderr=subprocess.STDOUT,
        )
        processes.append(lag)
        client = None
        if args.clients:
            clientlog = (folder / "clients.log").open("w")
            logs.append(clientlog)
            client = subprocess.Popen(
                [
                    str(JAVA),
                    *manifest["jvmFlags"]["loadtest"],
                    "-jar",
                    "loadtest/target/loadtest-0.1.0-SNAPSHOT-exec.jar",
                    str(args.clients),
                    str(args.seconds),
                    str(Path(args.dataset).with_suffix(".assets.json")),
                    "ws://localhost:18082/ws",
                    str(args.all),
                    str(args.slow),
                    str(args.concurrency),
                    str(folder),
                ],
                cwd=ROOT,
                stdout=clientlog,
                stderr=subprocess.STDOUT,
            )
            processes.append(client)
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                if '"event":"ready"' in (folder / "clients.log").read_text():
                    break
                if client.poll() is not None:
                    raise RuntimeError("Client setup failed before ready")
                time.sleep(0.25)
            else:
                raise TimeoutError("Clients did not become ready in 180s")
        started = time.time()
        manifest["replayStartedEpochMs"] = int(started * 1000)
        (folder / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        if args.jfr:

            def dump_recordings():
                time.sleep(2)
                for index in range(1, 7):
                    for module, process in [
                        ("gateway", gateway),
                        ("api", api),
                        ("processor", processor),
                    ]:
                        if process.poll() is not None:
                            continue
                        with (folder / "jfr-dumps.log").open("a") as out:
                            try:
                                subprocess.run(
                                    [
                                        "/opt/homebrew/opt/openjdk@21/bin/jcmd",
                                        str(process.pid),
                                        "JFR.dump",
                                        f"filename={folder}/{module}-snapshot-{index}.jfr",
                                    ],
                                    stdout=out,
                                    stderr=subprocess.STDOUT,
                                    timeout=5,
                                )
                            except subprocess.TimeoutExpired:
                                out.write(f"{module} dump {index} timed out\n")
                    time.sleep(3)

            threading.Thread(target=dump_recordings, daemon=True).start()
        if args.mode != "none":
            replaylog = (folder / "replayer.log").open("w")
            logs.append(replaylog)
            replay = subprocess.Popen(
                [
                    str(JAVA),
                    *manifest["jvmFlags"]["replayer"],
                    "-jar",
                    "replayer/target/replayer-0.1.0-SNAPSHOT-exec.jar",
                    args.dataset,
                    str(args.speed),
                    "200",
                    "http://localhost:18080",
                    "8",
                    args.mode,
                    str(args.switch_after),
                    "100",
                ],
                cwd=ROOT,
                stdout=replaylog,
                stderr=subprocess.STDOUT,
            )
            processes.append(replay)
            replay.wait(timeout=240)
            if replay.returncode:
                raise RuntimeError(f"Replayer failed: {replay.returncode}")
        if client:
            client.wait(timeout=max(60, args.seconds + 20))
            if client.returncode:
                raise RuntimeError(f"Client generator failed: {client.returncode}")
        # Observe drain, including a 2-second minimum after producer completion.
        time.sleep(2)
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            lines = (
                (folder / "lag.jsonl").read_text().splitlines()
                if (folder / "lag.jsonl").exists()
                else []
            )
            samples = [json.loads(line) for line in lines if '"lag"' in line]
            if samples and samples[-1]["lag"] == 0:
                break
            time.sleep(1)
        else:
            raise TimeoutError("Kafka lag did not drain in 90 seconds")
        snapshot(folder, "after")
        compare(folder)
        status = {
            "status": "completed",
            "observedSeconds": time.time() - started,
            "peakLag": max((sample["lag"] for sample in samples), default=None),
            "lastLag": samples[-1]["lag"],
            "finishedEpochMs": int(time.time() * 1000),
        }
    except Exception as error:
        status = {
            "status": "failed",
            "error": str(error),
            "finishedEpochMs": int(time.time() * 1000),
        }
        try:
            snapshot(folder, "after")
            compare(folder)
        except Exception:
            pass
    finally:
        (folder / "status.json").write_text(json.dumps(status, indent=2) + "\n")
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
        for process in reversed(processes):
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for log in logs:
            log.close()
    print(json.dumps(dict(scenario=args.scenario, **status)), flush=True)
    return 0 if status["status"] == "completed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
