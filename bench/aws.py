#!/usr/bin/env python3
"""Operator-run multi-host trials on an existing fleet-bench inventory; no AWS create APIs."""
import argparse
import csv
import hashlib
import json
import re
import shlex
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "infra/aws/scripts"))
from deploy import ssh, update_nginx
from metrics import read_metrics


def cpu_manifest(host, key):
    return ssh(
        host,
        key,
        "uname -a; lscpu; free -b; sudo docker info --format '{{json .}}'; chronyc tracking; chronyc sources -v",
        capture_output=True,
        text=True,
    ).stdout


def clock_offset(text):
    value = re.search(r"System time\s*:\s*([0-9.eE+-]+) seconds", text)
    normal = re.search(r"Leap status\s*:\s*Normal", text)
    if not value or not normal:
        raise ValueError("Chrony is not synchronized or tracking output is missing")
    return abs(float(value.group(1))) * 1000


def container(inventory, module, tag, options, mounts="", name=None, detached=False):
    image = inventory["images"][module] + ":" + tag
    cmd = f"sudo docker run {'-d' if detached else '--rm'} --network host --memory 4g --entrypoint java "
    if name:
        cmd += "--name " + shlex.quote(name) + " "
    return (
        cmd
        + mounts
        + " "
        + shlex.join(
            [
                image,
                "-Xms512m",
                "-Xmx3g",
                "-jar",
                "/app/service.jar",
                *map(str, options),
            ]
        )
    )


def reset(inventory, key, processors, gateways, quota):
    hosts = inventory["hosts"]
    apps = [
        n
        for n, h in hosts.items()
        if h["role"] in ("api", "copilot", "processor", "gateway")
    ]
    for name in apps:
        ssh(hosts[name], key, "sudo docker stop fleet >/dev/null")
    sql = (
        "DELETE FROM alert_rule; DELETE FROM outbox; DELETE FROM geofence_event; DELETE FROM asset_fence_state; DELETE FROM asset; UPDATE tenant SET ingest_rate_per_sec="
        + str(quota)
        + ";"
    )
    ssh(
        hosts["mysql"],
        key,
        "sudo docker exec -i fleet sh -c 'MYSQL_PWD=$MYSQL_PASSWORD mysql -ufleet fleet'",
        input=sql.encode(),
    )
    ssh(hosts["redis"], key, "sudo docker exec fleet redis-cli FLUSHDB")
    load = hosts["loadgen-1"]
    ssh(
        load,
        key,
        container(
            inventory,
            "loadtest",
            TAG,
            ["reset-kafka", hosts["kafka"]["private_ip"] + ":9092"],
        ),
    )
    selected = (
        ["api", "copilot"]
        + [f"processor-{i}" for i in range(1, processors + 1)]
        + [f"gateway-{i}" for i in range(1, gateways + 1)]
    )
    for name in selected:
        host = hosts[name]
        ssh(host, key, "sudo docker start fleet >/dev/null")
        port = {"api": 8080, "copilot": 8083, "processor": 8081, "gateway": 8082}[
            host["role"]
        ]
        ssh(
            host,
            key,
            f"timeout 180 bash -c 'until curl -fsS http://localhost:{port}/actuator/health >/dev/null; do sleep 2; done'",
        )
    update_nginx(inventory, key, gateways)
    time.sleep(
        5
    )  # Group assignment warmup; manifests and lag observer expose failures.


def metrics(host, key):
    port = {"api": 8080, "processor": 8081, "gateway": 8082}[host["role"]]
    return ssh(
        host,
        key,
        f"curl -fsS http://localhost:{port}/actuator/prometheus",
        capture_output=True,
        text=True,
    ).stdout


def merge_histograms(paths):
    bins = {}
    for path in paths:
        with path.open() as stream:
            for row in csv.DictReader(stream):
                ms = int(row["latency_ms"])
                bins[ms] = bins.get(ms, 0) + int(row["count"])
    total = sum(bins.values())
    percentiles = {}
    for p in [0.5, 0.95, 0.99]:
        cumulative = 0
        value = None
        for ms, count in sorted(bins.items()) if total else []:
            cumulative += count
            if cumulative >= total * p:
                value = ms
                break
        percentiles[f"p{int(p*100)}Ms"] = value
    return {"samples": total, **percentiles}


def record_collection_errors(out, errors):
    path = out / "status.json"
    status = json.loads(path.read_text()) if path.exists() else {}
    existing = status.get("collection_errors", [])
    status.update(
        outcome="incomplete", collection_errors=list(dict.fromkeys(existing + errors))
    )
    path.write_text(json.dumps(status) + "\n")


def run_trial(args, inventory, case, processors, gateways, clients, slow, trial):
    out = ROOT / "bench/results/AWS" / case / f"trial-{trial}"
    out.mkdir(parents=True, exist_ok=False)
    hosts = inventory["hosts"]
    active = (
        ["api", "copilot", "kafka", "redis", "mysql", "nginx"]
        + [f"processor-{i}" for i in range(1, processors + 1)]
        + [f"gateway-{i}" for i in range(1, gateways + 1)]
    )
    loadgens = sorted(n for n, h in hosts.items() if h["role"] == "loadgen")
    active += loadgens
    selected = sorted(hosts)
    manifest = {
        "case": case,
        "trial": trial,
        "inventory": inventory,
        "imageTag": args.tag,
        "dataset": str(args.dataset),
        "datasetSha256": hashlib.sha256(args.dataset.read_bytes()).hexdigest(),
        "processors": processors,
        "gateways": gateways,
        "clients": clients,
        "activeHosts": active,
        "inactiveHosts": [name for name in selected if name not in active],
        "slowPercent": slow,
        "seconds": args.seconds,
        "offeredRate": 0 if not clients else args.latency_rate,
        "tracingEnabled": False,
        "generatorJvm": "-Xms512m -Xmx3g",
        "generatorMemory": "4g",
        "serviceJvm": "-Xms256m -Xmx1g",
        "serviceMemory": "2g",
        "intervalTargetSeconds": 1,
        "maxClockOffsetMs": args.max_clock_offset_ms,
        "clockCaveat": "Receipt minus ingestedAt spans hosts; compare chrony offsets and sample uncertainty, not shared-clock laptop assumptions.",
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    background = []
    try:
        reset(inventory, args.key, processors, gateways, args.quota)
        # Provision dataset and subscriptions on each generator. One MMSI is sent by one lane.
        with args.dataset.open() as stream:
            assets = sorted({int(row["MMSI"]) for row in csv.DictReader(stream)})
        for name in loadgens:
            ssh(
                hosts[name],
                args.key,
                "cat > /opt/fleet/data/positions.csv",
                input=args.dataset.read_bytes(),
            )
            ssh(
                hosts[name],
                args.key,
                "cat > /opt/fleet/data/assets.json",
                input=json.dumps(assets).encode(),
            )
        services = [
            n for n in active if hosts[n]["role"] in ("api", "processor", "gateway")
        ]
        for name in selected:
            setup = cpu_manifest(hosts[name], args.key)
            (out / f"{name}-before.txt").write_text(setup)
            if clock_offset(setup) > args.max_clock_offset_ms:
                raise ValueError(
                    f"{name} clock offset exceeds configured bound before run"
                )
            remote = f"/opt/fleet/results/{case}-trial-{trial}"
            ssh(
                hosts[name],
                args.key,
                "mkdir -p "
                + shlex.quote(remote)
                + "; chmod 0777 "
                + shlex.quote(remote),
            )
            sampling_role = hosts[name]["role"] if name in active else "inactive"
            cmd = f"python3 /opt/fleet/source/infra/aws/scripts/sample.py {sampling_role} {args.seconds+240} {remote}"
            background.append(
                (
                    name,
                    remote,
                    subprocess.Popen(
                        [
                            "ssh",
                            "-i",
                            str(args.key),
                            "ec2-user@" + hosts[name]["public_ip"],
                            cmd,
                        ],
                        stdout=(out / f"{name}-sampler.log").open("w"),
                        stderr=subprocess.STDOUT,
                    ),
                )
            )
        lag_remote = f"/opt/fleet/results/{case}-trial-{trial}"
        lagcmd = container(
            inventory,
            "loadtest",
            args.tag,
            [
                "lag",
                hosts["kafka"]["private_ip"] + ":9092",
                "fleet-processor",
                args.seconds + 240,
                "/results/lag.jsonl",
            ],
            f"-v {lag_remote}:/results",
            name="fleet-lag",
            detached=True,
        )
        ssh(hosts[loadgens[0]], args.key, lagcmd)
        if clients:
            for i, name in enumerate(loadgens):
                count = clients // len(loadgens) + (i < clients % len(loadgens))
                if count == 0:
                    continue
                remote = f"/opt/fleet/results/{case}-trial-{trial}"
                cmd = container(
                    inventory,
                    "loadtest",
                    args.tag,
                    [
                        count,
                        args.seconds,
                        "/data/assets.json",
                        f"ws://{hosts['nginx']['private_ip']}:8082/ws",
                        100,
                        slow,
                        200,
                        "/results",
                        "/results/start",
                    ],
                    f"-v /opt/fleet/data:/data:ro -v {remote}:/results",
                    name="fleet-load",
                    detached=True,
                )
                ssh(hosts[name], args.key, cmd)
            deadline = time.monotonic() + 120
            for name in loadgens:
                while True:
                    logs = ssh(
                        hosts[name],
                        args.key,
                        "sudo docker logs fleet-load",
                        capture_output=True,
                        text=True,
                    ).stdout
                    (out / f"{name}-setup-clients.log").write_text(logs)
                    ready = [
                        json.loads(line)
                        for line in logs.splitlines()
                        if line.startswith("{") and '"ready"' in line
                    ]
                    if ready:
                        if ready[-1].get("failed"):
                            raise RuntimeError(f"{name} had failed connections")
                        break
                    if time.monotonic() > deadline:
                        raise TimeoutError("Generators never became ready")
                    time.sleep(1)
            for name in services:
                (out / f"{name}-before.prom").write_text(metrics(hosts[name], args.key))
            with args.dataset.open() as source:
                reader = csv.DictReader(source)
                fields = reader.fieldnames
                rows = []
                count = args.latency_rate * (args.seconds + 5)
                for row in reader:
                    rows.append(row)
                    if len(rows) == count:
                        break
                if len(rows) < count:
                    raise ValueError(
                        "Latency dataset is too short for the measurement window at the offered rate"
                    )
            import io

            buffer = io.StringIO()
            writer = csv.DictWriter(buffer, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
            ssh(
                hosts[loadgens[0]],
                args.key,
                "cat > /opt/fleet/data/replay.csv",
                input=buffer.getvalue().encode(),
            )
            # All clients ready before simultaneous bounded measurement starts.
            for name in loadgens:
                ssh(
                    hosts[name],
                    args.key,
                    f"touch /opt/fleet/results/{case}-trial-{trial}/start",
                )
        if not clients:
            for name in services:
                (out / f"{name}-before.prom").write_text(metrics(hosts[name], args.key))
        replay = container(
            inventory,
            "replayer",
            args.tag,
            [
                "/data/replay.csv" if clients else "/data/positions.csv",
                60,
                200,
                f"http://{hosts['api']['private_ip']}:8080",
                12,
                "max",
                0,
                100,
                args.latency_rate if clients else 0,
            ],
            "-v /opt/fleet/data:/data:ro",
        )
        began = time.monotonic()
        manifest["replayStartedAtMs"] = int(time.time() * 1000)
        if clients:
            starts = []
            for name in loadgens:
                stamp = ssh(
                    hosts[name],
                    args.key,
                    f"stat -c %Y /opt/fleet/results/{case}-trial-{trial}/start",
                    capture_output=True,
                    text=True,
                ).stdout.strip()
                starts.append(int(stamp) * 1000)
            manifest["measurementStartedAtMs"] = min(starts)
            manifest["measurementEndedAtMs"] = min(starts) + args.seconds * 1000
        (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        result = ssh(
            hosts[loadgens[0]], args.key, replay, capture_output=True, text=True
        )
        (out / "replayer.log").write_text(result.stdout + result.stderr)
        elapsed = time.monotonic() - began
        if clients:
            for name in loadgens:
                exit_code = ssh(
                    hosts[name],
                    args.key,
                    "sudo docker wait fleet-load",
                    capture_output=True,
                    text=True,
                ).stdout.strip()
                (out / f"{name}-clients.log").write_text(
                    ssh(
                        hosts[name],
                        args.key,
                        "sudo docker logs fleet-load",
                        capture_output=True,
                        text=True,
                    ).stdout
                )
                if exit_code != "0":
                    raise RuntimeError(f"{name} load generator exited {exit_code}")
        # Wait for lag drain, bounded. Never report completion on an undrained batch.
        limit = time.monotonic() + 120
        while True:
            payload = ssh(
                hosts[loadgens[0]],
                args.key,
                f"tail -1 {lag_remote}/lag.jsonl",
                capture_output=True,
                text=True,
            ).stdout
            lag = json.loads(payload)
            if lag.get("lag") == 0:
                break
            if time.monotonic() > limit:
                raise TimeoutError("Consumer lag did not drain")
            time.sleep(1)
        processing_duration = time.monotonic() - began
        manifest["lagDrainedAtMs"] = int(time.time() * 1000)
        (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        deltas = {}
        for name in services:
            after = metrics(hosts[name], args.key)
            (out / f"{name}-after.prom").write_text(after)
            a = read_metrics(after)
            b = read_metrics((out / f"{name}-before.prom").read_text())
            deltas[name] = {k: v - b.get(k, 0) for k, v in a.items()}
        (out / "metric-deltas.json").write_text(json.dumps(deltas, indent=2) + "\n")
        for name in selected:
            setup = cpu_manifest(hosts[name], args.key)
            (out / f"{name}-after.txt").write_text(setup)
            if clock_offset(setup) > args.max_clock_offset_ms:
                raise ValueError(
                    f"{name} clock offset exceeds configured bound after run"
                )
        replay_result = next(
            json.loads(line)
            for line in result.stdout.splitlines()
            if line.startswith("{") and "acknowledged" in line
        )
        rejected = sum(
            value
            for key, value in deltas["api"].items()
            if key.startswith("ingest_rate_rejected_total")
        )
        if clients and (replay_result["http429Retries"] or rejected):
            raise RuntimeError("Latency trial had HTTP 429s")
        (out / "replayer.json").write_text(json.dumps(replay_result, indent=2) + "\n")
        (out / "status.json").write_text(
            json.dumps(
                {
                    "outcome": "complete",
                    "replayWallSeconds": elapsed,
                    "processingWallSeconds": processing_duration,
                }
            )
            + "\n"
        )
    except Exception as error:
        (out / "status.json").write_text(
            json.dumps({"outcome": "failed", "error": str(error)}) + "\n"
        )
    finally:
        collection_errors = []
        for name, remote, process in background:
            try:
                ssh(
                    hosts[name],
                    args.key,
                    "pkill -f '^python3 /opt/fleet/source/infra/aws/scripts/sample.py' || true",
                )
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    process.wait(timeout=5)
                local = out / name
                local.mkdir(exist_ok=True)
                subprocess.run(
                    [
                        "scp",
                        "-i",
                        str(args.key),
                        "-r",
                        f"ec2-user@{hosts[name]['public_ip']}:{remote}/.",
                        str(local),
                    ],
                    check=True,
                )
            except Exception as error:
                (out / f"{name}-collection-error.txt").write_text(str(error))
                collection_errors.append(name + ": " + str(error))
        for name in loadgens:
            try:
                ssh(
                    hosts[name],
                    args.key,
                    "sudo docker rm -f fleet-load >/dev/null 2>&1 || true",
                )
            except Exception:
                pass
        try:
            ssh(hosts[loadgens[0]], args.key, "sudo docker rm -f fleet-lag >/dev/null")
        except Exception:
            pass
        if collection_errors:
            record_collection_errors(out, collection_errors)
        hist = list(out.glob("loadgen-*/pipeline-latency.csv"))
        if clients and len(hist) != min(clients, len(loadgens)):
            collection_errors.append("Missing generator histogram(s)")
            record_collection_errors(out, collection_errors)
        if clients and len(hist) == min(clients, len(loadgens)):
            (out / "pipeline.json").write_text(
                json.dumps(merge_histograms(hist), indent=2) + "\n"
            )


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--inventory", type=Path, required=True)
    p.add_argument("--key", type=Path, required=True)
    p.add_argument("--tag", required=True)
    p.add_argument("--dataset", type=Path, required=True)
    p.add_argument("--seconds", type=int, default=120)
    p.add_argument("--latency-rate", type=int, default=1000)
    p.add_argument("--quota", type=int, default=250000)
    p.add_argument("--max-clock-offset-ms", type=float, default=5)
    p.add_argument("--cases", nargs="+", default=["S1", "S2", "S3"])
    p.add_argument(
        "--reset-demo",
        action="store_true",
        help="Required acknowledgement: deletes demo fleet state/topics before each trial",
    )
    args = p.parse_args()
    if not args.reset_demo:
        p.error("--reset-demo is required; use disposable inventory only")
    inventory = json.loads(args.inventory.read_text())
    if inventory.get("project") != "fleet-bench":
        p.error("Inventory is not fleet-bench")
    TAG = args.tag
    for family in args.cases:
        cases = []
        if family == "S1":
            cases = [(f"S1-processors-{n}", n, 1, 0, 0) for n in [1, 2, 4]]
        if family == "S2":
            cases = [
                (f"S2-company-{c}-gateways-{n}", 1, n, c, 0)
                for c in [5000, 10000]
                for n in [1, 2]
            ]
        if family == "S3":
            cases = [(f"S3-slow-{s}", 1, 2, 5000, s) for s in [0, 5]]
        for case, processors, gateways, clients, slow in cases:
            if (
                f"processor-{processors}" not in inventory["hosts"]
                or f"gateway-{gateways}" not in inventory["hosts"]
            ):
                p.error("Provision enough processors/gateways for selected cases")
            for trial in range(1, 4):
                run_trial(
                    args, inventory, case, processors, gateways, clients, slow, trial
                )
