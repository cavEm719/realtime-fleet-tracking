#!/usr/bin/env python3
"""Keep raw service scrapes and produce counter/timer deltas without Prometheus."""
import argparse
import json
import re
import urllib.request
from pathlib import Path

SERVICES = {"api": 18080, "processor": 18081, "gateway": 18082}
PREFIXES = (
    "ingest_",
    "processor_",
    "geofence_",
    "gateway_",
    "copilot_",
    "http_server_requests_",
    "process_cpu_",
    "jvm_gc_",
)


def read_metrics(text):
    result = {}
    for line in text.splitlines():
        if line.startswith("#") or not line:
            continue
        match = re.match(r"^(.+) ([^ ]+)$", line)
        if match and match[1].startswith(PREFIXES):
            try:
                result[match[1]] = float(match[2])
            except ValueError:
                pass
    return result


def snapshot(directory, phase):
    for service, port in SERVICES.items():
        with urllib.request.urlopen(
            f"http://localhost:{port}/actuator/prometheus", timeout=5
        ) as response:
            text = response.read().decode()
        (directory / f"{service}-{phase}.prom").write_text(text)


def compare(directory):
    result = {}
    for service in SERVICES:
        before = read_metrics((directory / f"{service}-before.prom").read_text())
        after = read_metrics((directory / f"{service}-after.prom").read_text())
        result[service] = {
            key: value - before.get(key, 0) for key, value in after.items()
        }
    (directory / "metric-deltas.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("phase", choices=["before", "after", "compare"])
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)
    if args.phase == "compare":
        print(json.dumps(compare(args.directory)))
    else:
        snapshot(args.directory, args.phase)
