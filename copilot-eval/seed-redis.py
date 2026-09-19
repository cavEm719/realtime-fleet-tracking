#!/usr/bin/env python3
"""Seed the generated deterministic fixture on the disposable Redis host."""
import json
import subprocess
from fixture import build


def seed(run=subprocess.run):
    for asset in build()["assets"]:
        position = asset["position"]
        tenant, id_ = asset["tenantId"], asset["id"]
        run(
            [
                "sudo",
                "docker",
                "exec",
                "fleet",
                "redis-cli",
                "HSET",
                f"tenant:{tenant}:asset:{id_}:latest",
                "position_ts",
                str(position["positionTs"]),
                "position",
                json.dumps(position),
            ],
            check=True,
            capture_output=True,
        )
        run(
            [
                "sudo",
                "docker",
                "exec",
                "fleet",
                "redis-cli",
                "GEOADD",
                f"geo:{{{tenant}}}",
                str(position["lon"]),
                str(position["lat"]),
                str(id_),
            ],
            check=True,
            capture_output=True,
        )


if __name__ == "__main__":
    seed()
    print("Seeded 25 latest positions and tenant-scoped GEO members.")
