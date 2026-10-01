#!/usr/bin/env python3
"""Summarize actual Phase 6.1 trials; failed runs remain visible, never imputed."""
import csv
import json
import statistics
from collections import defaultdict
from pathlib import Path
from metrics import read_metrics

import sys

# Optional argument selects another results phase, e.g. Phase9.
ROOT = Path(__file__).resolve().parent / "results" / (sys.argv[1] if len(sys.argv) > 1 else "Phase61")


def metric(metrics, prefix, predicate=lambda key: True):
    return sum(
        value
        for key, value in metrics.items()
        if key.startswith(prefix) and predicate(key)
    )


def flaps(folder):
    path = folder / "events.tsv"
    if not path.exists():
        return None
    counts = {"ENTER": 0, "EXIT": 0, "exitEnterWithin30m": 0, "enterExitWithin10m": 0}
    previous = {}
    for event in csv.DictReader(path.open(), delimiter="\t"):
        kind = event["type"]
        counts[kind] += 1
        pair = event["asset_id"], event["fence_id"]
        timestamp = int(event["position_ts"])
        earlier = previous.get(pair)
        if earlier:
            old_type, old_time = earlier
            elapsed = timestamp - old_time
            if old_type == "EXIT" and kind == "ENTER" and 0 <= elapsed <= 1800000:
                counts["exitEnterWithin30m"] += 1
            elif old_type == "ENTER" and kind == "EXIT" and 0 <= elapsed <= 600000:
                counts["enterExitWithin10m"] += 1
        previous[pair] = kind, timestamp
    counts["totalFlaps"] = counts["exitEnterWithin30m"] + counts["enterExitWithin10m"]
    (folder / "flaps.json").write_text(json.dumps(counts, indent=2) + "\n")
    return counts


def main():
    rows = []
    for path in sorted(ROOT.rglob("status.json")):
        folder = path.parent
        status = json.loads(path.read_text())
        manifest = json.loads((folder / "manifest.json").read_text())
        clients = (
            json.loads((folder / "clients.json").read_text())
            if (folder / "clients.json").exists()
            else {}
        )
        deltas = (
            json.loads((folder / "metric-deltas.json").read_text())
            if (folder / "metric-deltas.json").exists()
            else {}
        )
        # Raw scrapes are authoritative, including trials collected before the label parser fix.
        recalculated = {}
        for afterpath in folder.glob("*-after.prom"):
            service = afterpath.name.removesuffix("-after.prom")
            beforepath = folder / f"{service}-before.prom"
            if beforepath.exists():
                before = read_metrics(beforepath.read_text())
                after = read_metrics(afterpath.read_text())
                recalculated[service] = {
                    key: value - before.get(key, 0) for key, value in after.items()
                }
        if recalculated:
            deltas = recalculated
            (folder / "metric-deltas.json").write_text(
                json.dumps(deltas, indent=2) + "\n"
            )
        gateway = {
            str(i) + key: value
            for i, (name, values) in enumerate(deltas.items())
            if name.startswith("gateway")
            for key, value in values.items()
        }
        api = deltas.get("api", {})
        processor = [
            values for name, values in deltas.items() if name.startswith("processor")
        ]
        replay = {}
        if (folder / "replayer.log").exists():
            for line in (folder / "replayer.log").read_text().splitlines():
                if line.startswith("{") and "acknowledged" in line:
                    replay = json.loads(line)
        heap = (
            read_metrics((folder / "gateway-heap.prom").read_text())
            if (folder / "gateway-heap.prom").exists()
            else {}
        )
        processed = sum(
            metric(values, "processor_redis_accepted_total")
            + metric(values, "processor_redis_filtered_total")
            for values in processor
        )
        lagpath = folder / "lag.jsonl"
        if lagpath.exists() and manifest.get("replayFinishedEpochMs"):
            samples = [
                json.loads(line)
                for line in lagpath.read_text().splitlines()
                if '"lag"' in line
            ]
            zeros = [
                sample["timeMs"]
                for sample in samples
                if sample["timeMs"] >= manifest["replayFinishedEpochMs"]
                and sample["lag"] == 0
            ]
            if zeros:
                manifest["drainedEpochMs"] = min(zeros)
        duration = (
            manifest.get("drainedEpochMs", 0) - manifest.get("replayStartedEpochMs", 0)
        ) / 1000
        frames = sum(
            value
            for key, value in gateway.items()
            if "gateway_frames_sent_total" in key
        )
        live = [
            read_metrics(path.read_text()) for path in folder.glob("gateway*-live.prom")
        ]
        oom = (folder / "gateway.log").exists() and "OutOfMemoryError" in (
            folder / "gateway.log"
        ).read_text()
        row = {
            "run": str(folder.relative_to(ROOT)),
            "status": status["status"],
            "error": status.get("error"),
            "gatewayOutOfMemory": oom,
            "serverOpenSessionsBeforeClientAbort": (
                sum(value.get("gateway_sessions_open", 0) for value in live)
                if live
                else None
            ),
            "tracingEnabled": manifest["tracingEnabled"],
            "connected": clients.get("connected"),
            "failedClients": clients.get("failed"),
            "terminatedObserved": clients.get("terminated"),
            "slowTerminatedObserved": clients.get("slowTerminatedObserved"),
            "ingestPositionsPerSecond": replay.get("positionsPerSecond"),
            "processedPositions": processed if processor else None,
            "processorDrainWindowSeconds": duration if duration > 0 else None,
            "processorDrainPositionsPerSecond": (
                processed / duration if duration > 0 and processor else None
            ),
            "peakLag": status.get("peakLag"),
            "lastLag": status.get("lastLag"),
            "framesPerSecond": clients.get("messagesPerSecond"),
            "serverFrames": frames if deltas else None,
            "pipelineP50Ms": clients.get("pipelineP50Ms"),
            "pipelineP95Ms": clients.get("pipelineP95Ms"),
            "pipelineP99Ms": clients.get("pipelineP99Ms"),
            "endToEndP99Ms": clients.get("p99Ms"),
            "pipelineSamples": clients.get("pipelineSamples"),
            "pipelineMissing": clients.get("pipelineMissing"),
            "pipelineNegative": clients.get("pipelineNegative"),
            "api429": (
                metric(
                    api,
                    "http_server_requests_seconds_count",
                    lambda key: 'status="429"' in key,
                )
                if deltas
                else None
            ),
            "replayer429": replay.get("http429Retries"),
            "replayer503": replay.get("http503Retries"),
            "gatewayLiveHeapMiB": (
                heap.get("jvm_gc_live_data_size_bytes", 0) / 1048576 if heap else None
            ),
            "gatewayGcPauseMs": (
                sum(
                    value * 1000
                    for key, value in gateway.items()
                    if "jvm_gc_pause_seconds_sum" in key
                )
                if deltas
                else None
            ),
            "overflowTerminations": (
                sum(
                    value
                    for key, value in gateway.items()
                    if "gateway_sessions_terminated_total" in key
                    and 'reason="overflow"' in key
                )
                if deltas
                else None
            ),
            "sendTimeTerminations": (
                sum(
                    value
                    for key, value in gateway.items()
                    if "gateway_sessions_terminated_total" in key
                    and 'reason="send_time"' in key
                )
                if deltas
                else None
            ),
            "flaps": flaps(folder),
        }
        row["latencyQualified"] = (
            (
                status["status"] == "completed"
                and bool(clients.get("pipelineSamples"))
                and clients.get("connected") == manifest["clients"]
                and clients.get("failed") == 0
                and row["api429"] == 0
                and row["replayer429"] == 0
                and clients.get("pipelineMissing") == 0
                and clients.get("pipelineNegative") == 0
            )
            if manifest["clients"]
            else None
        )
        rows.append(row)
    groups = defaultdict(list)
    for row in rows:
        groups[row["run"].rsplit("/", 1)[0]].append(row)
    aggregate = {}
    for name, group in groups.items():
        completed = [row for row in group if row["status"] == "completed"]
        aggregate[name] = {
            "attempts": len(group),
            "completed": len(completed),
            "metrics": {},
        }
        for key in rows[0] if rows else []:
            values = [
                row[key]
                for row in completed
                if isinstance(row.get(key), (int, float))
                and not isinstance(row[key], bool)
            ]
            if values:
                aggregate[name]["metrics"][key] = {
                    "median": statistics.median(values),
                    "min": min(values),
                    "max": max(values),
                }
    result = {"runs": rows, "aggregates": aggregate}
    (ROOT / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(aggregate, indent=2))


if __name__ == "__main__":
    main()
