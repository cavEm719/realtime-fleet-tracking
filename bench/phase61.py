#!/usr/bin/env python3
"""Run matched Phase 6.1 trials sequentially; retain every failed attempt."""
import argparse
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run(name, *options):
    subprocess.run(
        ["python3", "bench/run61.py", "Phase61/" + name, *options],
        cwd=ROOT,
        check=False,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=["before", "after", "scale"])
    parser.add_argument("--before-jar", default="/tmp/fleet61-before-gateway.jar")
    args = parser.parse_args()
    if args.stage in ["before", "after"]:
        for count, repetitions in [(5000, 3), (10000, 1)]:
            for trial in range(1, repetitions + 1):
                extra = (
                    ["--gateway-jar", args.before_jar] if args.stage == "before" else []
                )
                run(
                    f"S2-{args.stage}-{count}/trial-{trial}",
                    "--dataset",
                    "data/west-coast-10m.csv",
                    "--clients",
                    str(count),
                    "--seconds",
                    "70",
                    "--mode",
                    "speed",
                    "--speed",
                    "10",
                    "--rate",
                    "1000",
                    *extra,
                )
    if args.stage == "after":
        for trial in range(1, 4):
            run(f"S1/trial-{trial}")
        for slow in [0, 5]:
            for trial in range(1, 4):
                run(
                    f"S3-slow-{slow}/trial-{trial}",
                    "--clients",
                    "1000",
                    "--seconds",
                    "110",
                    "--slow",
                    str(slow),
                    "--offered-rate",
                    "3000",
                    "--rate",
                    "2000",
                )
        for mode in ["naive", "hysteresis"]:
            run(f"S5-{mode}/trial-1", "--geofence", mode)
    if args.stage == "scale":
        for processors in [1, 2]:
            for trial in range(1, 4):
                run(
                    f"scale-processors-probe-{processors}/trial-{trial}",
                    "--scale",
                    "--processors",
                    str(processors),
                    "--clients",
                    "100",
                    "--all",
                    "0",
                    "--seconds",
                    "20",
                    "--rate",
                    "1000000",
                )
        for gateways in [1, 2]:
            for trial in range(1, 4):
                run(
                    f"scale-gateways-{gateways}/trial-{trial}",
                    "--scale",
                    "--gateways",
                    str(gateways),
                    "--clients",
                    "5000",
                    "--seconds",
                    "70",
                    "--dataset",
                    "data/west-coast-10m.csv",
                    "--mode",
                    "speed",
                    "--speed",
                    "10",
                    "--rate",
                    "1000",
                )


if __name__ == "__main__":
    main()
