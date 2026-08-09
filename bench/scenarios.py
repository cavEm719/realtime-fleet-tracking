#!/usr/bin/env python3
"""Recorded scenario commands; execute selected names, sequentially."""
import argparse
import subprocess
import sys
from pathlib import Path

CASES = {
    "S1/valid": ["--dataset", "data/west-coast-4h.csv", "--rate", "10000"],
    **{
        f"S2/{mode}-{n}": [
            "--dataset",
            "data/west-coast-10m.csv",
            "--clients",
            str(n),
            "--all",
            str(all_percent),
            "--seconds",
            "20",
            "--rate",
            "2000",
        ]
        for mode, all_percent in [("tenant", 100), ("asset", 0)]
        for n in [1000, 5000, 10000]
    },
    "S3/normal": [
        "--dataset",
        "data/west-coast-4h.csv",
        "--clients",
        "1000",
        "--seconds",
        "120",
        "--rate",
        "1000",
    ],
    "S3/slow": [
        "--dataset",
        "data/west-coast-4h.csv",
        "--clients",
        "1000",
        "--slow",
        "5",
        "--seconds",
        "120",
        "--rate",
        "1000",
    ],
    "S4/burst": [
        "--dataset",
        "data/west-coast-10m.csv",
        "--mode",
        "speed",
        "--speed",
        "10",
        "--switch-after",
        "10",
        "--rate",
        "10000",
        "--clients",
        "1000",
        "--seconds",
        "25",
    ],
    "S5/naive": [
        "--dataset",
        "data/west-coast-4h.csv",
        "--geofence",
        "naive",
        "--rate",
        "10000",
    ],
    "S5/hysteresis": [
        "--dataset",
        "data/west-coast-4h.csv",
        "--geofence",
        "hysteresis",
        "--rate",
        "10000",
    ],
    "S6/storm": [
        "--dataset",
        "data/west-coast-10m.csv",
        "--mode",
        "none",
        "--clients",
        "5000",
        "--concurrency",
        "5000",
        "--seconds",
        "5",
    ],
}
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("names", nargs="+", choices=CASES.keys())
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
for name in args.names:
    command = [sys.executable, "bench/run.py", name, *CASES[name]]
    print("RUN", " ".join(command), flush=True)
    result = subprocess.run(command, cwd=root)
    print("EXIT", name, result.returncode, flush=True)
