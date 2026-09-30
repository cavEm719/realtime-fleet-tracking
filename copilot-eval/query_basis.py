"""Independent, versioned transparency checks. Does not import or change frozen graders."""
import json
from datetime import datetime, timedelta, timezone

TOOLS = {"getAssetStatus", "listEvents", "findNearby", "listFences", "createAlertRule",
         "findAssets", "resolveTimeRange", "aggregateEvents", "getFleetState", "queryEvents"}


def bounds(window, now):
    current = datetime.fromisoformat(now.replace("Z", "+00:00"))
    midnight = current.replace(hour=0, minute=0, second=0, microsecond=0)
    one_ms = timedelta(milliseconds=1)
    ranges = {
        "today": (midnight, current),
        "yesterday": (midnight - timedelta(days=1), midnight - one_ms),
        "last24h": (current - timedelta(days=1), current - one_ms),
        "last7days": (current - timedelta(days=7), current - one_ms),
        "thisWeek": (midnight - timedelta(days=current.weekday()), current),
    }
    return tuple(int(t.timestamp() * 1000) for t in ranges[window])


def canonical(tool, arguments, now):
    args = json.loads(arguments) if isinstance(arguments, str) else dict(arguments)
    if args == {"": {}} and tool in {"listFences", "getFleetState"}:
        args = {}
    if tool == "queryEvents":
        spec = args["spec"]
        if spec.get("timeWindow") is not None:
            start, end = bounds(spec["timeWindow"], now)
        else:
            start, end = (int(datetime.fromisoformat(spec[k].replace("Z", "+00:00")).timestamp() * 1000)
                          for k in ("from", "to"))
        result = {"metric": spec["metric"], "groupBy": spec["groupBy"],
                  "ports": sorted(set(spec.get("ports") or [])),
                  "vessels": sorted(set(spec.get("vessels") or [])), "fromTs": start, "toTs": end}
        if spec.get("eventType") is not None:
            result["eventType"] = spec["eventType"]
    elif tool == "aggregateEvents":
        if args.get("window") is not None:
            start, end = bounds(args["window"], now)
        else:
            start, end = (int(datetime.fromisoformat(args[k].replace("Z", "+00:00")).timestamp() * 1000)
                          for k in ("fromUtc", "toUtc"))
        result = {k: v for k, v in args.items() if k in {"assetId", "fenceId", "eventType"} and v is not None}
        result.update(fromTs=start, toTs=end)
    elif tool == "resolveTimeRange":
        start, end = bounds(args["window"], now)
        result = dict(fromTs=start, toTs=end)
    else:
        result = {k: v for k, v in args.items() if v is not None}
        if tool in {"listEvents", "findNearby", "listFences"}:
            result["limit"] = min(50, args.get("limit") or 50)
        if tool == "getAssetStatus":
            result["stateLimit"] = 50
        if tool == "findAssets":
            result["name"] = result["name"].strip()
        if tool == "createAlertRule" and not result.get("assetIds"):
            result.pop("assetIds", None)  # protobuf omits an empty repeated field.
    return {k: json.dumps(v, separators=(",", ":"), ensure_ascii=False) if isinstance(v, list)
            else str(v) for k, v in result.items()}


def check(reply, tenant, fixture):
    """Compare basis with successful calls, normalized by documented v1 rules; no answer grading."""
    errors = []
    basis = reply.get("queryBasis")
    if not isinstance(basis, list) or not reply.get("answer", "").startswith("Query basis\n"):
        return ["missing query basis"]
    audit = reply.get("evaluation")
    if not audit or "tools" not in audit:
        return ["execution evidence unavailable: enable evaluation mode"]
    successful = []
    for call in audit["tools"]:
        try:
            data = json.loads(call["result"])
        except (ValueError, TypeError):
            errors.append("invalid tool result evidence")
            continue
        if isinstance(data, dict) and "error" in data:
            continue
        successful.append(call)
    if len(successful) != len(basis):
        errors.append("basis/call count mismatch")
    for entry, call in zip(basis, successful):
        tool = call["tool"]
        if tool not in TOOLS or entry.get("tool") != tool:
            errors.append("basis/call tool mismatch")
            continue
        try:
            expected = canonical(tool, call["arguments"], audit["utcNow"])
        except (KeyError, TypeError, ValueError):
            errors.append("cannot normalize executed call")
            continue
        actual = entry.get("arguments", {})
        # Redis radius floating-point formatting is equivalent to an integer literal.
        if tool == "findNearby":
            for field in ("radiusM", "lat", "lon"):
                if field in expected and field in actual:
                    try:
                        if float(expected[field]) == float(actual[field]):
                            expected[field] = actual[field]
                    except ValueError:
                        pass
        if actual != expected:
            errors.append(f"{tool}: validated arguments differ from executed arguments")
        if not entry.get("text") or "results" not in entry or "assumptions" not in entry:
            errors.append(f"{tool}: incomplete structured basis")
        text = entry.get("text", "")
        for field in ("fromTs", "toTs"):
            if field in expected:
                instant = datetime.fromtimestamp(int(expected[field]) / 1000, timezone.utc)
                iso = instant.isoformat(timespec="milliseconds" if instant.microsecond else "seconds").replace("+00:00", "Z")
                if iso not in text:
                    errors.append(f"{tool}: displayed time differs from executed bound")
        if tool == "queryEvents":
            if "Group by: " + expected["groupBy"] not in text:
                errors.append("queryEvents: displayed grouping differs")
            label = "distinct vessels" if expected["metric"] == "distinct_vessels" else "event count"
            if "Metric: " + label not in text:
                errors.append("queryEvents: displayed metric differs")
        if expected.get("eventType") and "(" + expected["eventType"] + ")" not in text:
            errors.append(f"{tool}: displayed event type differs")
        ids = []
        for field in ("fenceId", "assetId"):
            if field in actual:
                ids.append(("fences" if field == "fenceId" else "assets", int(actual[field])))
        for field, collection in (("ports", "fences"), ("vessels", "assets"), ("assetIds", "assets")):
            for identity in json.loads(actual.get(field, "[]")):
                ids.append((collection, identity))
        for collection, identity in ids:
            owned = [r for r in fixture[collection] if r["tenantId"] == tenant and r["id"] == identity]
            if len(owned) != 1 or owned[0]["name"] not in entry.get("text", ""):
                errors.append(f"{tool}: missing tenant-owned database name")
    return errors


if __name__ == "__main__":
    import argparse
    from pathlib import Path
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("replies", type=Path, help="JSON list of {tenantId, reply} with evaluation evidence")
    parser.add_argument("--fixture", type=Path, default=Path(__file__).with_name("fixture.json"))
    arguments = parser.parse_args()
    fixture = json.loads(arguments.fixture.read_text())
    rows = [{"index": n, "errors": check(r["reply"], r["tenantId"], fixture)}
            for n, r in enumerate(json.loads(arguments.replies.read_text()))]
    print(json.dumps({"checkerVersion": "query-basis-v1", "rows": rows}, indent=2))
    raise SystemExit(1 if any(r["errors"] for r in rows) else 0)
