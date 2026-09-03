#!/usr/bin/env python3
"""Aggregate only measured complete AWS trials; preserve min/max and failures."""
import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path
from aws import clock_offset, merge_histograms
from resource_evidence import summarize


def triple(values):
    values = [v for v in values if v is not None]
    return (
        None
        if not values
        else {
            "median": statistics.median(values),
            "min": min(values),
            "max": max(values),
            "trials": len(values),
        }
    )


def gateway_frame_rate(evidence, active_hosts):
    rates = [
        evidence["hosts"][name]
        .get("sampledCounterRates", {})
        .get("gateway_frames_sent_total")
        for name in active_hosts
        if name.startswith("gateway-")
    ]
    return sum(rates) if rates and all(value is not None for value in rates) else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--cases", nargs="+", help="Include only these measured scenario folders"
    )
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent / "results/AWS"
    groups = defaultdict(list)
    failed = []
    for path in sorted(root.glob("*/trial-*/status.json")):
        folder = path.parent
        if args.cases and folder.parent.name not in args.cases:
            continue
        status = json.loads(path.read_text())
        if status["outcome"] != "complete":
            failed.append({"path": str(folder.relative_to(root)), **status})
            continue
        replay = json.loads((folder / "replayer.json").read_text())
        pipeline = (
            json.loads((folder / "pipeline.json").read_text())
            if (folder / "pipeline.json").exists()
            else {}
        )
        clients = [
            json.loads(file.read_text())
            for file in folder.glob("loadgen-*/clients.json")
        ]
        row = {
            "ingestPositionsPerSecond": replay["positionsPerSecond"],
            "connected": sum(c["connected"] for c in clients),
            "failedClients": sum(c["failed"] for c in clients),
            "terminated": sum(c["terminated"] for c in clients),
            "slowTerminatedObserved": sum(
                c.get("slowTerminatedObserved", 0) for c in clients
            ),
            "normalTerminatedObserved": sum(
                c["terminated"] - c.get("slowTerminatedObserved", 0) for c in clients
            ),
            "pipelineOverflow": sum(c.get("pipelineOverflow", 0) for c in clients),
            "pipelineNegative": sum(c.get("pipelineNegative", 0) for c in clients),
            "pipelineMissing": sum(c.get("pipelineMissing", 0) for c in clients),
            "messagesPerSecond": sum(c["messagesPerSecond"] for c in clients),
            **pipeline,
        }
        secondary = (
            merge_histograms(list(folder.glob("loadgen-*/latency.csv")))
            if clients
            else {}
        )
        row.update(
            {
                "endToEnd" + key[0].upper() + key[1:]: value
                for key, value in secondary.items()
            }
        )
        row["http429Retries"] = replay.get("http429Retries")
        row["http503Retries"] = replay.get("http503Retries")
        row["acknowledgedPositions"] = replay["acknowledged"]
        deltas = json.loads((folder / "metric-deltas.json").read_text())
        manifest = json.loads((folder / "manifest.json").read_text())
        row["apiRateRejected"] = sum(
            value
            for key, value in deltas.get("api", {}).items()
            if key.startswith("ingest_rate_rejected_total")
        )
        offsets = [clock_offset(p.read_text()) for p in folder.glob("*-before.txt")]
        offsets += [clock_offset(p.read_text()) for p in folder.glob("*-after.txt")]
        row["maxClockOffsetMs"] = max(offsets) if offsets else None
        if manifest.get("measurementStartedAtMs") or manifest.get("replayStartedAtMs"):
            evidence = summarize(folder)
            (folder / "resource-evidence.json").write_text(
                json.dumps(evidence, indent=2) + "\n"
            )
            row["peakConsumerLag"] = (
                evidence["consumerLag"]["total"]["max"]
                if evidence["consumerLag"]["total"]
                else None
            )
        row["gatewayOverflowTerminations"] = sum(
            value
            for name, metrics in deltas.items()
            if name.startswith("gateway-")
            for key, value in metrics.items()
            if key.startswith("gateway_sessions_terminated_total")
            and 'reason="overflow"' in key
        )
        frame_count = sum(
            value
            for name, metrics in deltas.items()
            if name.startswith("gateway-")
            for key, value in metrics.items()
            if key.startswith("gateway_frames_sent_total")
        )
        row["gatewayFramesPerSecond"] = (
            gateway_frame_rate(evidence, manifest["activeHosts"])
            if clients
            and (
                manifest.get("measurementStartedAtMs")
                or manifest.get("replayStartedAtMs")
            )
            else None
        )
        row["gatewayFramesTotalSnapshotDelta"] = frame_count
        # Counters are total accepted positions; matched wall time includes ingest + lag drain.
        duration = status.get("processingWallSeconds")
        counts = sum(
            value
            for name, metrics in deltas.items()
            if name.startswith("processor-")
            for key, value in metrics.items()
            if key.startswith("processor_redis_accepted_total")
            or key.startswith("processor_redis_filtered_total")
        )
        row["processorPositionsPerSecond"] = counts / duration if duration else None
        errors = 0
        overruns = 0
        cpus = defaultdict(list)
        for file in folder.glob("*/host.jsonl"):
            for line in file.read_text().splitlines():
                sample = json.loads(line)
                errors += len(sample.get("errors", []))
                overruns += bool(sample.get("overrun"))
                if sample.get("hostCpuPercent") is not None:
                    cpus[file.parent.name].append(sample["hostCpuPercent"])
        (folder / "resource-summary.json").write_text(
            json.dumps(
                {
                    "hostCpuPercent": {k: triple(v) for k, v in cpus.items()},
                    "samplingErrors": errors,
                    "samplingOverruns": overruns,
                },
                indent=2,
            )
            + "\n"
        )
        groups[folder.parent.name].append(row)
    result = {
        "scenarios": {
            case: {key: triple([row.get(key) for row in rows]) for key in rows[0]}
            for case, rows in groups.items()
        },
        "failedTrials": failed,
        "status": "unmeasured" if not groups else "measured",
        "caveat": "Pipeline histograms are merged by counts across generators; never average percentiles. Inspect per-trial resource and chrony evidence before attributing scale-out.",
    }
    root.mkdir(parents=True, exist_ok=True)
    (args.output or root / "summary.json").write_text(
        json.dumps(result, indent=2) + "\n"
    )
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
