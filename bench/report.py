#!/usr/bin/env python3
"""Summarize raw recorded measurements; never estimate missing values."""
import json
from pathlib import Path

root = Path(__file__).resolve().parent / "results"
rows = []
for statusfile in sorted(root.rglob("status.json")):
    folder = statusfile.parent
    status = json.loads(statusfile.read_text())
    manifest = json.loads((folder / "manifest.json").read_text())
    clients = (
        json.loads((folder / "clients.json").read_text())
        if (folder / "clients.json").exists()
        else {}
    )
    metrics = (
        json.loads((folder / "metric-deltas.json").read_text())
        if (folder / "metric-deltas.json").exists()
        else {}
    )
    replay = {}
    if (folder / "replayer.log").exists():
        for line in (folder / "replayer.log").read_text().splitlines():
            if line.startswith("{") and "acknowledged" in line:
                replay = json.loads(line)
    samples = []
    if (folder / "lag.jsonl").exists():
        samples = [
            json.loads(line)
            for line in (folder / "lag.jsonl").read_text().splitlines()
            if '"lag"' in line
        ]
    recovery = None
    if samples:
        peak = max(range(len(samples)), key=lambda i: samples[i]["lag"])
        zeros = [sample for sample in samples[peak + 1 :] if sample["lag"] == 0]
        if zeros and samples[peak]["lag"] > 0:
            recovery = (zeros[0]["timeMs"] - samples[peak]["timeMs"]) / 1000
    processor = metrics.get("processor", {})
    total = processor.get("processor_redis_accepted_total", 0) + processor.get(
        "processor_redis_filtered_total", 0
    )
    rows.append(
        {
            "run": str(folder.relative_to(root)),
            "status": status["status"],
            "error": status.get("error"),
            "dataset": manifest["dataset"],
            "clients": clients.get("connected"),
            "failedClients": clients.get("failed"),
            "terminatedObserved": clients.get("terminated"),
            "framesPerSecond": clients.get("messagesPerSecond"),
            "positionSamples": clients.get("positionSamples"),
            "p50Ms": clients.get("p50Ms"),
            "p95Ms": clients.get("p95Ms"),
            "p99Ms": clients.get("p99Ms"),
            "connectionP99Ms": clients.get("connectionP99Ms"),
            "ingestPositionsPerSecond": replay.get("positionsPerSecond"),
            "processedPositions": total if metrics else None,
            "processorWindowPositionsPerSecond": (
                total / status["observedSeconds"]
                if metrics and status.get("observedSeconds")
                else None
            ),
            "peakLag": status.get("peakLag"),
            "peakToNextZeroSeconds": recovery,
            "transitions": {
                key: value
                for key, value in processor.items()
                if key.startswith("geofence_transitions_")
            },
            "gatewayTerminations": {
                key: value
                for key, value in metrics.get("gateway", {}).items()
                if key.startswith("gateway_sessions_terminated_")
            },
        }
    )
(root / "summary.json").write_text(json.dumps(rows, indent=2) + "\n")
print(json.dumps(rows, indent=2))
