#!/usr/bin/env python3
"""Rerun the laptop scenarios on the post-requirements code under bench/results/Phase9."""
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run(name, *options):
    subprocess.run(["python3", "bench/run61.py", "Phase9/" + name, *options], cwd=ROOT, check=False)


for trial in range(1, 4):
    run(f"S2-5000/trial-{trial}", "--dataset", "data/west-coast-10m.csv", "--clients", "5000",
        "--seconds", "70", "--mode", "speed", "--speed", "10", "--rate", "1000")
for trial in range(1, 4):
    run(f"S1/trial-{trial}")
for slow in [0, 5]:
    for trial in range(1, 4):
        run(f"S3-slow-{slow}/trial-{trial}", "--clients", "1000", "--seconds", "110",
            "--slow", str(slow), "--offered-rate", "3000", "--rate", "2000")
