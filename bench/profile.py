#!/usr/bin/env python3
"""Aggregate retained JFR samples within the measured replay window."""
import argparse
from collections import Counter
from datetime import datetime
import json
import gzip
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("directory", type=Path)
args = parser.parse_args()
manifest = json.loads((args.directory / "manifest.json").read_text())
start = manifest.get("replayStartedEpochMs", manifest["startedEpochMs"])
result = {}
for module in ["api", "processor", "gateway"]:
    recording = args.directory / f"{module}.jfr"
    candidates = [recording, *sorted(args.directory.glob(f"{module}-snapshot-*.jfr"))]
    valid = [file for file in candidates if file.exists() and file.stat().st_size > 0]
    if not valid:
        result[module] = {
            "error": "No nonempty recording; exit dump was lost after failure"
        }
        continue
    recording = max(valid, key=lambda file: file.stat().st_size)
    data = args.directory / f"{module}-samples.json"
    with data.open("w") as output:
        subprocess.run(
            [
                "/opt/homebrew/opt/openjdk@21/bin/jfr",
                "print",
                "--json",
                "--events",
                "jdk.ExecutionSample,jdk.ObjectAllocationSample",
                str(recording),
            ],
            stdout=output,
            check=True,
        )
    samples = json.loads(data.read_text())["recording"]["events"]
    with data.open("rb") as source, gzip.open(str(data) + ".gz", "wb") as compressed:
        import shutil

        shutil.copyfileobj(source, compressed)
    data.unlink()
    cpu, allocations, threads, position_classes = (
        Counter(),
        Counter(),
        Counter(),
        Counter(),
    )
    count = 0
    for event in samples:
        values = event["values"]
        stamp = (
            datetime.fromisoformat(
                values["startTime"].replace("Z", "+00:00")
            ).timestamp()
            * 1000
        )
        if stamp < start:
            continue
        frames = (values.get("stackTrace") or {}).get("frames") or []
        names = [
            frame["method"]["type"]["name"].replace("/", ".")
            + "."
            + frame["method"]["name"]
            for frame in frames
        ]
        app = next((name for name in names if name.startswith("dev.fleet.")), None)
        if event["type"] == "jdk.ExecutionSample":
            count += 1
            for name in set(names):
                cpu[name] += 1
            thread = (values.get("sampledThread") or {}).get("javaName", "unknown")
            threads[thread] += 1
        elif app:
            allocations[app] += values.get("weight", 0)
            if app == "dev.fleet.gateway.ClientSession.position":
                position_classes[
                    values["objectClass"]["name"].replace("/", ".")
                ] += values.get("weight", 0)
    result[module] = {
        "executionSamples": count,
        "topInclusiveFrames": cpu.most_common(20),
        "applicationAllocationWeightsBytes": allocations.most_common(20),
        "threads": threads.most_common(10),
        "positionAllocationClasses": position_classes.most_common(10),
        "recordingUsed": recording.name,
        "windowStartEpochMs": start,
    }
(args.directory / "profile-summary.json").write_text(
    json.dumps(result, indent=2) + "\n"
)
print(json.dumps(result, indent=2))
