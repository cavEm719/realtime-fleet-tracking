#!/usr/bin/env python3
"""One-second host samples; actual timestamps/durations and collection errors retained."""
import argparse
import json
import subprocess
import signal
import time
import re
import threading
import urllib.request
from pathlib import Path


def command(args):
    return subprocess.run(
        args, text=True, capture_output=True, check=True, timeout=0.8
    ).stdout


def record_container_stats(stream, output):
    for line in stream:
        cleaned = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", line).strip()
        if not cleaned:
            continue
        try:
            row = json.loads(cleaned)
            row["timeMs"] = int(time.time() * 1000)
        except (ValueError, TypeError):
            row = {"timeMs": int(time.time() * 1000), "error": cleaned}
        output.write(json.dumps(row) + "\n")
        output.flush()


def parse_network_limits(text):
    counters = {}
    for line in text.splitlines():
        if ":" not in line:
            continue
        name, value = line.strip().split(":", 1)
        if "allowance" in name and value.strip().isdigit():
            counters[name] = int(value.strip())
    return counters


def main():
    p = argparse.ArgumentParser()
    p.add_argument("role")
    p.add_argument("seconds", type=int)
    p.add_argument("output", type=Path)
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    stats = (args.output / "docker-stats.jsonl").open("w")
    docker = subprocess.Popen(
        ["sudo", "docker", "stats", "--format", "{{json .}}"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    stats_reader = threading.Thread(
        target=record_container_stats, args=(docker.stdout, stats), daemon=True
    )
    stats_reader.start()
    running = True

    def stop(signum, frame):
        nonlocal running
        running = False

    signal.signal(signal.SIGTERM, stop)
    previous = None
    deadline = time.monotonic() + args.seconds
    try:
        with (args.output / "host.jsonl").open("w") as out:
            while running and time.monotonic() < deadline:
                began = time.monotonic()
                sample = {
                    "timeMs": int(time.time() * 1000),
                    "role": args.role,
                    "errors": [],
                }
                cpu = [
                    int(n)
                    for n in Path("/proc/stat").read_text().splitlines()[0].split()[1:]
                ]
                if previous:
                    delta = [a - b for a, b in zip(cpu, previous)]
                    # Linux user/nice/system/idle/iowait/irq/softirq/steal; guest already included.
                    total = sum(delta[:8])
                    sample["hostCpuPercent"] = (
                        100 * (1 - (delta[3] + delta[4]) / total) if total else None
                    )
                    sample["stealPercent"] = 100 * delta[7] / total if total else None
                previous = cpu
                sample["network"] = Path("/proc/net/dev").read_text()
                sample["meminfo"] = Path("/proc/meminfo").read_text()
                try:
                    sample["networkAllowanceCounters"] = parse_network_limits(
                        command(["sudo", "ethtool", "-S", "ens5"])
                    )
                except Exception as error:
                    sample["networkCountersError"] = str(error)
                try:
                    if args.role == "redis":
                        sample["redisInfo"] = command(
                            [
                                "sudo",
                                "docker",
                                "exec",
                                "fleet",
                                "redis-cli",
                                "INFO",
                                "all",
                            ]
                        )
                    if args.role == "mysql":
                        sample["mysqlStatus"] = command(
                            [
                                "sudo",
                                "docker",
                                "exec",
                                "fleet",
                                "sh",
                                "-c",
                                "MYSQL_PWD=$MYSQL_PASSWORD mysql -ufleet -N -e 'SHOW GLOBAL STATUS' fleet",
                            ]
                        )
                    if args.role in ("api", "processor", "gateway"):
                        port = {"api": 8080, "processor": 8081, "gateway": 8082}[
                            args.role
                        ]
                        with urllib.request.urlopen(
                            f"http://localhost:{port}/actuator/prometheus", timeout=0.8
                        ) as reply:
                            sample["prometheus"] = reply.read().decode()
                except Exception as error:
                    sample["errors"].append(str(error))
                sample["sampleDurationSeconds"] = time.monotonic() - began
                sample["overrun"] = sample["sampleDurationSeconds"] > 1
                out.write(json.dumps(sample) + "\n")
                out.flush()
                time.sleep(max(0, 1 - (time.monotonic() - began)))
    finally:
        docker.terminate()
        try:
            docker.wait(timeout=5)
        except subprocess.TimeoutExpired:
            docker.kill()
        docker.wait()
        stats_reader.join(timeout=5)
        stats.close()


if __name__ == "__main__":
    main()
