#!/usr/bin/env python3
"""Export only repositories from local Terraform state after an ECR-only apply."""
import argparse
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]


def inventory(state, planned=False, hosts=False):
    images = {}
    planned_hosts = {}

    def read(module):
        for resource in module.get("resources", []):
            if (
                resource.get("type") == "aws_ecr_repository"
                and resource.get("name") == "service"
            ):
                values = resource["values"]
                url = values.get("repository_url")
                if not url and planned:
                    url = "planned.dkr.ecr.us-east-1.amazonaws.com/" + values["name"]
                if not url:
                    raise ValueError("Repository URL absent from applied state")
                images[resource["index"]] = url
            if (
                hosts
                and resource.get("type") == "aws_instance"
                and resource.get("name") == "host"
            ):
                name = resource["index"]
                values = resource["values"]
                ordinal = len(planned_hosts) + 10
                planned_hosts[name] = {
                    "role": values["tags"]["role"],
                    "instance_type": values["instance_type"],
                    "public_ip": values.get("public_ip")
                    or (f"192.0.2.{ordinal}" if planned else None),
                    "private_ip": values.get("private_ip")
                    or (f"10.42.1.{ordinal}" if planned else None),
                }
                if not all(planned_hosts[name].values()):
                    raise ValueError("Applied host addresses absent")
        for child in module.get("child_modules", []):
            read(child)

    read(
        state.get("planned_values" if planned else "values", {}).get("root_module", {})
    )
    if set(images) != {"api", "copilot"}:
        raise ValueError("Evaluation requires exactly the API and copilot repositories")
    return {
        "project": "fleet-bench",
        "region": "us-east-1",
        "hosts": planned_hosts,
        "planned": planned,
        "images": images,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--plan-json", type=Path)
    parser.add_argument("--include-hosts", action="store_true")
    args = parser.parse_args()
    if args.plan_json:
        state = json.loads(args.plan_json.read_text())
    else:
        result = subprocess.run(
            ["terraform", "-chdir=infra/aws", "show", "-json"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=True,
        )
        state = json.loads(result.stdout)
    args.output.write_text(
        json.dumps(
            inventory(state, planned=bool(args.plan_json), hosts=args.include_hosts),
            indent=2,
        )
        + "\n"
    )
