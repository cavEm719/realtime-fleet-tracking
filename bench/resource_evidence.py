#!/usr/bin/env python3
"""Summarize retained host evidence without assuming which component is the bottleneck."""
import argparse
import json
import math
import re
import statistics
from pathlib import Path
from metrics import read_metrics


def stats(values):
    if not values:
        return None
    ordered = sorted(values)
    return {
        "samples": len(values),
        "mean": statistics.mean(values),
        "p95": ordered[max(0, math.ceil(len(ordered) * 0.95) - 1)],
        "max": max(values),
    }


def read_rows(path):
    if not path.exists():
        return []
    rows = []
    for line in path.read_text().splitlines():
        cleaned = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", line).strip()
        if cleaned.startswith("{"):
            rows.append(json.loads(cleaned))
    return rows


def network(text):
    for line in text.splitlines():
        if line.strip().startswith("ens5:"):
            fields = line.split(":", 1)[1].split()
            return {
                "rxBytes": int(fields[0]),
                "txBytes": int(fields[8]),
                "rxDrops": int(fields[3]),
                "txDrops": int(fields[11]),
            }
    return {}


def redis_info(text):
    result = {}
    for line in text.splitlines():
        if line.startswith("#") or ":" not in line:
            continue
        name, value = line.split(":", 1)
        if name.startswith("cmdstat_"):
            for item in value.split(","):
                key, number = item.split("=", 1)
                try:
                    result[name + "." + key] = float(number)
                except ValueError:
                    pass
        else:
            try:
                result[name] = float(value)
            except ValueError:
                pass
    return result


def counter_delta(rows, accessor, keys):
    if len(rows) < 2:
        return None
    snapshots = [accessor(row) for row in rows]
    first, last = snapshots[0], snapshots[-1]
    result = {}
    for key in keys:
        if key not in first or key not in last:
            continue
        values = [snapshot[key] for snapshot in snapshots if key in snapshot]
        reset = any(b < a for a, b in zip(values, values[1:]))
        result[key] = None if reset else last[key] - first[key]
    return result


def host_evidence(folder, start, end):
    all_rows = read_rows(folder / "host.jsonl")
    rows = [r for r in all_rows if start <= r["timeMs"] <= end]
    cpu = [r["hostCpuPercent"] for r in rows if r.get("hostCpuPercent") is not None]
    before = folder.parent / (folder.name + "-before.txt")
    text = before.read_text() if before.exists() else ""
    cores = re.search(r"^CPU\(s\):\s*(\d+)", text, re.MULTILINE)
    vcpus = int(cores.group(1)) if cores else None
    result = {
        "hostCpuPercent": stats(cpu),
        "hostCpuSamplesAtLeast90Percent": sum(x >= 90 for x in cpu),
        "stealPercent": stats([r["stealPercent"] for r in rows if "stealPercent" in r]),
        "vcpus": vcpus,
        "sampleErrors": sum(bool(r.get("errors")) for r in rows),
        "samplingOverruns": sum(bool(r.get("overrun")) for r in rows),
    }
    prom_rows = [r for r in rows if r.get("prometheus")]
    if len(prom_rows) >= 2:
        elapsed = (prom_rows[-1]["timeMs"] - prom_rows[0]["timeMs"]) / 1000
        keys = [
            "gateway_frames_sent_total",
            "ingest_positions_acknowledged_total",
            "processor_redis_accepted_total",
            "processor_redis_filtered_total",
        ]
        deltas = counter_delta(prom_rows, lambda r: read_metrics(r["prometheus"]), keys)
        result["sampledCounterRates"] = {
            key: value / elapsed if value is not None and elapsed > 0 else None
            for key, value in deltas.items()
        }
        result["counterRateWindowSeconds"] = elapsed
    containers = read_rows(folder / "docker-stats.jsonl")
    timestamped = [r for r in containers if "timeMs" in r]
    source = (
        [r for r in timestamped if start <= r["timeMs"] <= end]
        if timestamped
        else containers
    )
    result["containerSampleErrors"] = sum("error" in row for row in source)
    by_name = {}
    for row in source:
        if "CPUPerc" not in row:
            continue
        by_name.setdefault(row["Name"], []).append(row)
    result["containerWindow"] = (
        "measurement"
        if timestamped
        else "whole collection; original samples lack timestamps"
    )
    result["containers"] = {
        name: {
            "cpuPercentOfOneCore": stats([float(r["CPUPerc"].rstrip("%")) for r in rs]),
            "hostEquivalentCpuPercent": (
                stats([float(r["CPUPerc"].rstrip("%")) / vcpus for r in rs])
                if vcpus
                else None
            ),
            "memoryLimitPercent": stats(
                [float(r["MemPerc"].rstrip("%")) for r in rs if r.get("MemPerc")]
            ),
        }
        for name, rs in by_name.items()
    }
    rates = {"rxMiBPerSec": [], "txMiBPerSec": []}
    for a, b in zip(rows, rows[1:]):
        seconds = (b["timeMs"] - a["timeMs"]) / 1000
        if seconds <= 0:
            continue
        x, y = network(a["network"]), network(b["network"])
        for key, label in [("rxBytes", "rxMiBPerSec"), ("txBytes", "txMiBPerSec")]:
            if key in x and key in y and y[key] >= x[key]:
                rates[label].append((y[key] - x[key]) / seconds / 2**20)
    result["network"] = {key: stats(values) for key, values in rates.items()}
    ena_rows = [r for r in rows if r.get("networkAllowanceCounters")]
    keys = {
        k
        for r in ena_rows
        for k in r["networkAllowanceCounters"]
        if k.endswith("_exceeded")
    }
    result["networkAllowanceExceededDeltas"] = counter_delta(
        ena_rows, lambda r: r["networkAllowanceCounters"], keys
    )
    result["networkCounterErrors"] = sum(
        bool(r.get("networkCountersError")) for r in rows
    )
    redis_rows = [r for r in rows if r.get("redisInfo")]
    if len(redis_rows) >= 2:
        duration = (redis_rows[-1]["timeMs"] - redis_rows[0]["timeMs"]) / 1000
        values = counter_delta(
            redis_rows,
            lambda r: redis_info(r["redisInfo"]),
            [
                "used_cpu_sys",
                "used_cpu_user",
                "used_cpu_sys_main_thread",
                "used_cpu_user_main_thread",
            ],
        )
        lua_keys = [
            "cmdstat_" + cmd + "." + field
            for cmd in ["eval", "evalsha"]
            for field in ["usec", "calls"]
        ]
        lua_values = counter_delta(
            redis_rows,
            lambda r: {k: redis_info(r["redisInfo"]).get(k, 0) for k in lua_keys},
            lua_keys,
        )
        if any(value is None for value in lua_values.values()):
            result["redis"] = {"error": "Lua counters reset during measurement"}
            return result
        lua_usec = sum(
            value for key, value in lua_values.items() if key.endswith(".usec")
        )
        lua_calls = sum(
            value for key, value in lua_values.items() if key.endswith(".calls")
        )
        required = ["used_cpu_sys", "used_cpu_user"]
        if (
            duration <= 0
            or any(values.get(k) is None for k in required)
            or lua_usec < 0
            or lua_calls < 0
        ):
            result["redis"] = {
                "error": "Missing counters, nonpositive sample duration, or counters reset"
            }
            return result
        result["redis"] = {
            "durationSeconds": duration,
            "processCoreCpuPercent": 100
            * (values.get("used_cpu_sys", 0) + values.get("used_cpu_user", 0))
            / duration,
            "mainThreadCoreCpuPercent": (
                100
                * (
                    values.get("used_cpu_sys_main_thread", 0)
                    + values.get("used_cpu_user_main_thread", 0)
                )
                / duration
                if all(
                    values.get(k) is not None
                    for k in ["used_cpu_sys_main_thread", "used_cpu_user_main_thread"]
                )
                else None
            ),
            "luaCalls": lua_calls,
            "luaTotalUsec": lua_usec,
            "luaUsecPerCall": lua_usec / lua_calls if lua_calls else None,
            "luaMsPerSecond": lua_usec / duration / 1000,
            "luaScope": "Aggregate EVAL/EVALSHA, including ingest token buckets and position batches; not isolated script profiling",
        }
    return result


def summarize(folder):
    manifest = json.loads((folder / "manifest.json").read_text())
    start = manifest.get("measurementStartedAtMs", manifest.get("replayStartedAtMs"))
    end = manifest.get("measurementEndedAtMs", manifest.get("lagDrainedAtMs"))
    if not start or not end:
        raise ValueError(
            "Trial has no retained measurement window; supply explicit timestamps for legacy evidence"
        )
    return window_summary(folder, start, end)


def window_summary(folder, start, end):
    hosts = {
        p.name: host_evidence(p, start, end)
        for p in folder.iterdir()
        if p.is_dir() and (p / "host.jsonl").exists()
    }
    lags = [
        r
        for r in read_rows(folder / "loadgen-1/lag.jsonl")
        if start <= r["timeMs"] <= end
    ]
    members = {}
    for row in lags:
        for name, info in row.get("members", {}).items():
            member = members.setdefault(
                name, {"host": info["host"], "lags": [], "partitions": []}
            )
            member["lags"].append(info["lag"])
            member["partitions"] = info["partitions"]
    for member in members.values():
        member["lag"] = stats(member.pop("lags"))
    return {
        "windowStartMs": start,
        "windowEndMs": end,
        "durationSeconds": (end - start) / 1000,
        "hosts": hosts,
        "consumerLag": {
            "total": stats([r["lag"] for r in lags if "lag" in r]),
            "members": members,
            "errors": sum("error" in r for r in lags),
        },
        "caveat": "Host CPU is normalized across all vCPU; Docker CPU 100% means one core. Snapshot intervals can miss short stalls. Zero ENA allowance deltas rule out observed shaping, not every network delay. No saturated-resource attribution is fabricated from an isolated peak.",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = summarize(args.folder)
    if args.output:
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    else:
        print(json.dumps(result, indent=2))
