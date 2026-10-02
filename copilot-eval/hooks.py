"""Verification adapter; copilot itself never reads MySQL or controls services."""

import json
import hashlib
import sys
from pathlib import Path
from fixture import HERE, NOW

ROOT = HERE.parent
sys.path.insert(0, str(ROOT / "infra/aws/scripts"))
from deploy import ssh


class FleetHooks:
    def __init__(self, inventory, key, model, region="us-east-1"):
        self.inventory = json.loads(Path(inventory).read_text())
        if (
            self.inventory.get("project") != "fleet-bench"
            or self.inventory.get("region") != "us-east-1"
        ):
            raise ValueError(
                "Only the disposable us-east-1 fleet-bench inventory is allowed"
            )
        self.hosts = self.inventory["hosts"]
        self.key = Path(key)
        self.model = model
        self.region = region

    def command(self, name, command, **options):
        return ssh(
            self.hosts[name],
            self.key,
            command,
            capture_output=True,
            text=True,
            **options,
        ).stdout

    def validate(self, require_target=True):
        for filename in ("fixture.py", "fixture.json", "seed-redis.py"):
            expected = hashlib.sha256((HERE / filename).read_bytes()).hexdigest()
            actual = self.command(
                "redis", f"sha256sum /opt/fleet/source/copilot-eval/{filename}"
            ).split()[0]
            if actual != expected:
                raise ValueError(
                    "Remote fixture differs from local evaluation; redeploy first"
                )
        checks = [("COPILOT_FIXED_NOW", NOW), ("COPILOT_EVALUATION_ENABLED", "true")]
        if require_target:
            checks.extend([("COPILOT_MODEL", self.model), ("AWS_REGION", self.region)])
        for name, expected in checks:
            value = self.command(
                "copilot",
                'sudo docker exec fleet sh -c \'printf "%s" "$' + name + "\"'",
            )
            if value.strip() != expected:
                raise ValueError(
                    f"Deploy copilot with {name}={expected}; evaluation must match the fixture clock/model"
                )
        for name, host in self.hosts.items():
            if host["role"] in ("processor", "gateway"):
                if (
                    self.command(
                        name, "sudo docker inspect fleet --format '{{.State.Running}}'"
                    ).strip()
                    != "false"
                ):
                    raise ValueError(
                        "Stop streaming processors/gateways with seed-eval.py before evaluation"
                    )

    def mysql(self, sql):
        return self.command(
            "mysql",
            "sudo docker exec -i fleet sh -c 'MYSQL_PWD=$MYSQL_PASSWORD mysql --batch --raw --skip-column-names -ufleet fleet'",
            input=sql,
        )

    def judge(self, request, region="us-east-1"):
        raw = self.command(
            "loadgen-1",
            "python3 /opt/fleet/source/copilot-eval/judge-call.py --region " + region,
            input=json.dumps(request),
        )
        return json.loads(raw)

    def reseed(self):
        self.mysql((HERE / "seed.sql").read_text())
        self.command(
            "redis",
            "sudo docker exec fleet redis-cli FLUSHDB >/dev/null && python3 /opt/fleet/source/copilot-eval/seed-redis.py",
        )

    def rules(self):
        raw = self.mysql(
            "SELECT JSON_OBJECT('id',id,'tenantId',tenant_id,'fenceId',fence_id,'status',status,'assetFilter',asset_filter) FROM alert_rule ORDER BY id;"
        )
        rows = []
        for line in raw.splitlines():
            row = json.loads(line)
            filtered = row.pop("assetFilter")
            if isinstance(filtered, str):
                filtered = json.loads(filtered)
            row["assetIds"] = [] if filtered is None else filtered.get("assetIds", [])
            rows.append(row)
        return rows

    def down(self):
        self.command("api", "sudo docker stop fleet >/dev/null")

    def up(self):
        self.command(
            "api",
            "sudo docker start fleet >/dev/null; timeout 120 bash -c 'until curl -fsS http://localhost:8080/actuator/health >/dev/null; do sleep 1; done'",
        )
