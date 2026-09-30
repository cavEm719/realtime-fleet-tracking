"""Generate a separate transparency suite without editing reviewed tasks or ground truth."""
import json
from pathlib import Path
from query_basis import bounds

HERE = Path(__file__).resolve().parent


def build():
    fixture = json.loads((HERE / "fixture.json").read_text())
    cases = [
        ("ships-today", "How many ships arrived today?", "distinct_vessels", "today", "none", [], []),
        ("arrivals-today", "How many arrivals were there today?", "event_count", "today", "none", [], []),
        ("ports-calendar-week", "Arrivals per port this week, please.", "event_count", "thisWeek", "port", [], []),
        ("unique-ports-week", "Which ports received the most different vessels over the last seven days?", "distinct_vessels", "last7days", "port", [], []),
        ("atlas-week", "How many times did Atlas arrive over the last seven days?", "event_count", "last7days", "none", [], [366000000]),
        ("daily-la", "Show daily arrivals at Los Angeles over the last seven days.", "event_count", "last7days", "day", [1], []),
        ("vessels-yesterday", "Give me yesterday's arrival counts for each of our vessels.", "event_count", "yesterday", "vessel", [], []),
        ("chinese-ports", "昨天各港口分别有多少艘不同的船抵港？", "distinct_vessels", "yesterday", "port", [], []),
    ]
    tasks = []
    for identity, question, metric, window, grouping, ports, vessels in cases:
        start, end = bounds(window, fixture["now"])
        rows = [e for e in fixture["events"] if e["tenantId"] == 1 and e["type"] == "ENTER"
                and start <= e["positionTs"] <= end and (not ports or e["fenceId"] in ports)
                and (not vessels or e["assetId"] in vessels)]
        def value(events):
            return len(events) if metric == "event_count" else len({e["assetId"] for e in events})
        groups = {}
        from datetime import datetime, timezone
        for event in rows:
            key = {"port": event["fenceId"], "vessel": event["assetId"], "none": "none",
                   "day": datetime.fromtimestamp(event["positionTs"] / 1000, timezone.utc).strftime("%Y-%m-%d")}[grouping]
            groups.setdefault(str(key), []).append(event)
        if grouping == "none" and not groups:
            groups["none"] = []
        tasks.append(dict(id=identity, suiteVersion="query-transparency-v1", tenantId=1, turns=[question],
                          expectedTools=["queryEvents"],
                          spec=dict(metric=metric, eventType="ENTER", ports=ports, vessels=vessels,
                                    timeWindow=window, groupBy=grouping),
                          reference=dict(total=value(rows), groups={k: value(v) for k, v in sorted(groups.items())},
                                         fromTs=start, toTs=end),
                          transparencyRequired=True))
    return tasks


if __name__ == "__main__":
    (HERE / "query-transparency-v1.json").write_text(json.dumps(build(), ensure_ascii=False, indent=2) + "\n")
