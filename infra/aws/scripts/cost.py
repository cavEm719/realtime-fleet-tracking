#!/usr/bin/env python3
"""Public AWS price-list estimate; creates no resources and needs no credentials."""
import argparse
import gzip
import json
import urllib.request
from pathlib import Path

URL = "https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ec2-ondemand-without-sec-sel/US%20East%20%28N.%20Virginia%29/Linux/index.json"


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--inventory", type=Path)
    p.add_argument("--output", type=Path, default=Path("infra/aws/cost-estimate.json"))
    args = p.parse_args()
    with urllib.request.urlopen(URL, timeout=30) as response:
        raw = response.read()
    data = json.loads(gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw)
    rates = {
        v["Instance Type"]: float(v["price"])
        for v in data["regions"]["US East (N. Virginia)"].values()
    }
    sizes = ["m7g.large"] * 3 + ["r7g.large"] + ["c7g.large"] * 8 + ["c7g.xlarge"] * 2
    if args.inventory:
        inventory = json.loads(args.inventory.read_text())
        if inventory["region"] != "us-east-1":
            p.error(
                "This public price source covers only us-east-1; select a region-specific quote"
            )
        sizes = [h["instance_type"] for h in inventory["hosts"].values()]
    compute = sum(rates[size] for size in sizes)
    result = {
        "kind": "estimate, not measured spend",
        "source": URL,
        "publication": data["manifest"],
        "instances": sizes,
        "hourlyComputeUsd": compute,
        "hourlyPublicIpv4Usd": len(sizes) * 0.005,
        "hourly40GiBGp3Usd": len(sizes) * 40 * 0.08 / 730,
        "estimatedHourlyUsd": compute
        + len(sizes) * 0.005
        + len(sizes) * 40 * 0.08 / 730,
        "excluded": "Bedrock tokens, ECR storage/scans, internet transfer, taxes; adjust volume sizes and regional rates.",
    }
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
