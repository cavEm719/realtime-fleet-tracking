"""Manual HTTP evaluation for the NEW suite; never run automatically by Maven/tests."""
import argparse
import json
import time
import urllib.request
from pathlib import Path
from query_basis import canonical, check


def run(endpoint, tasks, fixture, send):
    results = []
    for task in tasks:
        started = time.monotonic()
        reply = send(endpoint.rstrip("/") + "/copilot/chat", {
            "tenantId": task["tenantId"], "userId": "transparency-eval",
            "message": task["turns"][0],
        })
        expected_spec = canonical("queryEvents", {"spec": task["spec"]}, fixture["now"])
        generic = [entry for entry in reply.get("queryBasis", []) if entry.get("tool") == "queryEvents"]
        diagnostics = {"genericQueryUsed": bool(generic),
                       "requestedSpecMatched": any(entry.get("arguments") == expected_spec for entry in generic)}
        totals = [row[1] for entry in generic for table in entry.get("results", [])
                  if table.get("columns") == ["Fact", "Value"] for row in table.get("rows", [])
                  if len(row) == 2 and row[0] == "total"]
        diagnostics["referenceTotalMatched"] = str(task["reference"]["total"]) in totals
        results.append({"id": task["id"], "suiteVersion": task["suiteVersion"],
                        "tenantId": task["tenantId"], "reference": task["reference"], "reply": reply,
                        "latencyMs": (time.monotonic() - started) * 1000,
                        "transparencyErrors": check(reply, task["tenantId"], fixture),
                        "toolDiagnostics": diagnostics})
    return results


def send(url, body):
    request = urllib.request.Request(url, data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--allow-model-calls", action="store_true")
    args = parser.parse_args()
    if not args.allow_model_calls:
        parser.error("Manual model execution requires --allow-model-calls")
    if args.output.exists():
        parser.error("Output already exists; historical evidence will not be overwritten")
    here = Path(__file__).resolve().parent
    tasks = json.loads((here / "query-transparency-v1.json").read_text())
    fixture = json.loads((here / "fixture.json").read_text())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(run(args.endpoint, tasks, fixture, send), ensure_ascii=False, indent=2) + "\n")
