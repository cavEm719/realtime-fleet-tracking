#!/usr/bin/env python3
"""Operator-run fixed demo seed on existing disposable hosts. Clears fleet data."""
import argparse
import json
from pathlib import Path
from deploy import ssh, ROOT

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--inventory", type=Path, required=True)
p.add_argument("--key", type=Path, required=True)
p.add_argument("--reset-demo", action="store_true")
a = p.parse_args()
if not a.reset_demo:
    p.error("--reset-demo is required")
inventory = json.loads(a.inventory.read_text())
if inventory.get("project") != "fleet-bench":
    p.error("Use disposable fleet-bench inventory only")
hosts = inventory["hosts"]
for host in hosts.values():
    if host["role"] in ("api", "copilot", "processor", "gateway"):
        ssh(host, a.key, "sudo docker stop fleet >/dev/null")
ssh(
    hosts["mysql"],
    a.key,
    "sudo docker exec -i fleet sh -c 'MYSQL_PWD=$MYSQL_PASSWORD mysql -ufleet fleet'",
    input=(ROOT / "copilot-eval/seed.sql").read_bytes(),
)
ssh(
    hosts["redis"],
    a.key,
    "sudo docker exec fleet redis-cli FLUSHDB; python3 /opt/fleet/source/copilot-eval/seed-redis.py",
)
for name, port in [("api", 8080), ("copilot", 8083)]:
    ssh(hosts[name], a.key, "sudo docker start fleet >/dev/null")
    ssh(
        hosts[name],
        a.key,
        f"timeout 180 bash -c 'until curl -fsS http://localhost:{port}/actuator/health >/dev/null; do sleep 2; done'",
    )
print(
    "Fixed seed installed. Processors/gateways remain stopped; evaluate against the internal copilot address."
)
