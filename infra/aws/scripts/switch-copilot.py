#!/usr/bin/env python3
"""Switch only the copilot container between the two approved evaluation models."""
import argparse
import json
from pathlib import Path
import shlex
from deploy import ssh


def switch(inventory, key, tag, model, region, dry_run=False):
    allowed = {"deepseek.v3-v1:0": "us-west-2", "openai.gpt-oss-120b-1:0": "us-east-1"}
    if (
        allowed.get(model) != region
        or inventory.get("project") != "fleet-bench"
        or inventory.get("region") != "us-east-1"
    ):
        raise ValueError("Only the two approved models on fleet-bench are permitted")
    if inventory.get("planned") and not dry_run:
        raise ValueError("Planned inventory cannot be used for live execution")
    host = inventory["hosts"]["copilot"]
    image = inventory["images"]["copilot"] + ":" + tag
    values = shlex.join(["COPILOT_MODEL=" + model, "AWS_REGION=" + region])
    run = shlex.join(
        [
            "sudo",
            "docker",
            "run",
            "-d",
            "--name",
            "fleet",
            "--restart",
            "unless-stopped",
            "--network",
            "host",
            "--env-file",
            "/opt/fleet/service.env",
            "--memory",
            "2g",
            "--entrypoint",
            "java",
            image,
            "-Xms256m",
            "-Xmx1g",
            "-jar",
            "/app/service.jar",
        ]
    )
    command = (
        "set -e; sed -i '/^COPILOT_MODEL=/d;/^AWS_REGION=/d' /opt/fleet/service.env; "
        + "printf '%s\\n' "
        + values
        + " >> /opt/fleet/service.env; "
        + "sudo docker rm -f fleet >/dev/null; "
        + run
        + "; "
        + "timeout 180 bash -c 'until curl -fsS http://localhost:8083/actuator/health >/dev/null; do sleep 2; done'"
    )
    if dry_run:
        print(json.dumps({"dryRun": True, "command": command}))
    else:
        ssh(host, key, command)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--key", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    switch(
        json.loads(args.inventory.read_text()),
        args.key,
        args.tag,
        args.model,
        args.region,
        args.dry_run,
    )
