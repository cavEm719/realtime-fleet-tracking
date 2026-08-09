#!/usr/bin/env python3
"""Stream a NOAA day, select a bounding box/time range, sort the retained rows."""
import argparse
import csv
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source", type=Path)
parser.add_argument("output", type=Path)
parser.add_argument("--start", default="2023-01-01T00:00:00")
parser.add_argument("--end", default="2023-01-01T04:00:00")
parser.add_argument(
    "--bbox",
    nargs=4,
    type=float,
    default=[-125, 32, -117, 49],
    metavar=("WEST", "SOUTH", "EAST", "NORTH"),
)
args = parser.parse_args()
west, south, east, north = args.bbox
rows, scanned, invalid = [], 0, 0
with args.source.open(newline="") as source:
    reader = csv.DictReader(source)
    fields = reader.fieldnames
    for row in reader:
        scanned += 1
        try:
            if (
                args.start <= row["BaseDateTime"].rstrip("Z") < args.end
                and south <= float(row["LAT"]) <= north
                and west <= float(row["LON"]) <= east
                and 100000000 <= int(row["MMSI"]) <= 999999999
            ):
                rows.append(row)
        except ValueError:
            invalid += 1
rows.sort(key=lambda row: (row["BaseDateTime"], int(row["MMSI"])))
args.output.parent.mkdir(parents=True, exist_ok=True)
with args.output.open("w", newline="") as target:
    writer = csv.DictWriter(target, fieldnames=fields)
    writer.writeheader()
    writer.writerows(rows)
assets = sorted({int(row["MMSI"]) for row in rows})
args.output.with_suffix(".assets.json").write_text(json.dumps(assets))
metadata = {
    "source": str(args.source),
    "subset": str(args.output),
    "scanned": scanned,
    "retained": len(rows),
    "assets": len(assets),
    "invalid": invalid,
    "start": args.start,
    "end": args.end,
    "bbox": args.bbox,
    "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
}
args.output.with_suffix(".metadata.json").write_text(
    json.dumps(metadata, indent=2) + "\n"
)
print(json.dumps(metadata))
