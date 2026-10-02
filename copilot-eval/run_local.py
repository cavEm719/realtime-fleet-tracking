#!/usr/bin/env python3
"""Run the v3 operator tasks against the local compose stack and DeepSeek's API (no AWS).

Run inside the copilot environment (it has redis-py):
    cd copilot && uv run python ../copilot-eval/run_local.py --output ../data/eval-v3

Before each task repetition the fixture is reseeded on the primary and the runner waits until
the replica has applied it. Outage tasks stop the API container; replica-delay tasks stop the
replica's applier until its data is more than 60 seconds old. Grading uses the frozen checker.
"""
import argparse
import json
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

import redis

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from bedrock import chat_request, execute_task  # noqa: E402
from fixture import build  # noqa: E402
from graders import row_data_leaks  # noqa: E402

# Peak-hour list prices, USD per million tokens (cache-miss input, output); conservative.
PRICES = {"deepseek-flash": (0.30, 1.20), "deepseek-v4-pro": (1.32, 3.96)}
CNY_PER_USD = 7.3
URLS = ["http://localhost:8083", "http://localhost:8084"]


class LocalHooks:
    def __init__(self, project: str):
        self.project = project
        self.mode = None
        self.redis = redis.Redis(port=6379)

    def _exec(self, service, *command, stdin=None):
        return subprocess.run(
            ["docker", "exec", "-i", f"{self.project}-{service}-1", *command],
            input=stdin, capture_output=True, text=True, check=True,
        ).stdout

    def _sql(self, service, sql, user="root", password="localroot"):
        return self._exec(service, "mysql", f"-u{user}", f"-p{password}", "-N", "fleet", stdin=sql)

    def reseed(self):
        self._sql("mysql", (HERE / "seed.sql").read_text(), "fleet", "fleet")
        self.redis.flushdb()
        for asset in build()["assets"]:
            p, key = asset["position"], f"tenant:{asset['tenantId']}:asset:{asset['id']}"
            self.redis.hset(key + ":latest", mapping={"position_ts": p["positionTs"], "position": json.dumps(p)})
            self.redis.geoadd(f"geo:{{{asset['tenantId']}}}", (p["lon"], p["lat"], str(asset["id"])))
        self._caught_up()

    def _caught_up(self, timeout=60):
        gtids = self._sql("mysql", "SELECT @@GLOBAL.gtid_executed;").strip().replace("\n", "")
        waited = self._sql("mysql-replica", f"SELECT WAIT_FOR_EXECUTED_GTID_SET('{gtids}', {timeout});")
        if waited.strip() != "0":
            raise RuntimeError("Replica did not apply the fixture in time")

    def rules(self):
        return []  # alert-rule drafts no longer exist; nothing may be written

    def down(self):
        if self.mode == "replica_lag":
            self._sql("mysql-replica", "STOP REPLICA SQL_THREAD;")
            lag = "SELECT TIMESTAMPDIFF(SECOND, beat_at, CURRENT_TIMESTAMP(6)) FROM replication_heartbeat;"
            while int(self._sql("mysql-replica", lag).strip()) <= 61:
                time.sleep(1)
        else:
            subprocess.run(["docker", "stop", f"{self.project}-api-1"], capture_output=True, check=True)

    def up(self):
        if self.mode == "replica_lag":
            self._sql("mysql-replica", "START REPLICA SQL_THREAD;")
            self._caught_up()
            return
        subprocess.run(["docker", "start", f"{self.project}-api-1"], capture_output=True, check=True)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                urllib.request.urlopen("http://localhost:8080/actuator/health", timeout=2)
                time.sleep(2)  # let the copilots' channels reconnect
                return
            except OSError:
                time.sleep(1)
        raise RuntimeError("API did not restart")


def chat_for(task):
    """Send turn i of the task conversation to the copilot instance named in turnInstances."""
    instances = task.get("turnInstances")
    counts = {}

    def chat(tenant, conversation, message):
        turn = counts.get(conversation, 0)
        counts[conversation] = turn + 1
        url = URLS[instances[turn]] if instances and turn < len(instances) else URLS[0]
        return chat_request(url, tenant, conversation, message)

    return chat


def cost_cny(rows, model):
    price_in, price_out = PRICES[model]
    usd = sum(r.get("input_tokens", 0) * price_in + r.get("output_tokens", 0) * price_out for r in rows) / 1e6
    return usd * CNY_PER_USD


def annotated(row):
    """Every data-backed answer must state how current the replicated port data is (N6.6)."""
    reply = row["responses"][-1]["reply"] if row.get("responses") else {}
    basis = [b for b in reply.get("queryBasis") or [] if b.get("dataAsOf")]
    return None if not basis else "Port events and states as of:" in reply.get("answer", "")


def summarize(rows, model):
    tasks = {}
    for row in rows:
        tasks.setdefault(row["id"], []).append(row)
    flags = [annotated(r) for r in rows]
    return {
        "model": model,
        "runs": len(rows),
        "solved": sum(r.get("task_success", False) for r in rows),
        "dataLeakRuns": sum(bool(r.get("data_leaks")) for r in rows),
        "errors": sum(bool(r.get("error")) for r in rows),
        "asOfAnnotated": f"{sum(f is True for f in flags)}/{sum(f is not None for f in flags)}",
        "inputTokens": sum(r.get("input_tokens", 0) for r in rows),
        "outputTokens": sum(r.get("output_tokens", 0) for r in rows),
        "estimatedCostCny": round(cost_cny(rows, model), 4),
        "tasks": {k: {"category": v[0]["category"], "passes": sum(r.get("task_success", False) for r in v),
                      "runs": len(v)} for k, v in tasks.items()},
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", default="deepseek-flash", choices=sorted(PRICES))
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--task-ids", nargs="*")
    parser.add_argument("--budget-cny", type=float, default=18.0)
    parser.add_argument("--project", default="fleet-eval")
    args = parser.parse_args()
    tasks = json.loads((HERE / "questions-v3.json").read_text())
    if args.task_ids:
        tasks = [t for t in tasks if t["id"] in args.task_ids]
    args.output.mkdir(parents=True, exist_ok=True)
    hooks, rows = LocalHooks(args.project), []
    with open(args.output / "runs.jsonl", "a") as log:
        for trial in range(1, args.repeats + 1):
            for task in tasks:
                if cost_cny(rows, args.model) >= args.budget_cny:
                    print("Budget guard reached; stopping", file=sys.stderr)
                    break
                hooks.mode = task.get("dependency")
                row = execute_task(task, trial, chat_for(task), hooks)
                row["data_leaks"] = row_data_leaks(row)
                rows.append(row)
                log.write(json.dumps(row, ensure_ascii=False) + "\n")
                log.flush()
                print(f"{task['id']} t{trial}: {'PASS' if row.get('task_success') else 'FAIL'}"
                      f" cost so far {cost_cny(rows, args.model):.3f} CNY", flush=True)
    summary = summarize(rows, args.model)
    (args.output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1))
    print(json.dumps({k: v for k, v in summary.items() if k != "tasks"}, indent=1))


if __name__ == "__main__":
    main()
