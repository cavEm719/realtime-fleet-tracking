#!/usr/bin/env python3
"""Compare retained full-evaluation evidence; no service or model calls."""
import argparse
import json
from pathlib import Path


def comparison(left, right):
    first, second = left["manifest"], right["manifest"]
    if any(m.get("purpose", "full") != "full" for m in (first, second)):
        raise ValueError("Pilots are separate evidence, not full evaluation results")
    for field in ("fixtureSha256", "fixedNow"):
        if not first.get(field) or first[field] != second.get(field):
            raise ValueError("Comparison requires the same fixture and clock")
    if sorted(first["plannedTasks"], key=lambda task: task["id"]) != sorted(
        second["plannedTasks"], key=lambda task: task["id"]
    ):
        raise ValueError("Comparison task wording or queries differ")
    cross = first.get("crossJudging") is True and second.get("crossJudging") is True
    judges = [m["bedrock"][m.get("judgeRole", "judge")] for m in (first, second)]
    targets = [m["bedrock"][m.get("targetRole", "target")] for m in (first, second)]
    ids = lambda values: [v.get("invocationId", v.get("profileId")) for v in values]
    if cross:
        if (
            ids(judges) != list(reversed(ids(targets)))
            or ids(targets)[0] == ids(targets)[1]
        ):
            raise ValueError(
                "Cross-judging requires opposite targets, never self-judging"
            )
    elif first.get("independentJudging") and second.get("independentJudging"):
        if any(t == j for t,j in zip(ids(targets),ids(judges))):
            raise ValueError("Independent judges cannot judge themselves")
    elif ids(judges)[0] != ids(judges)[1] or judges[0].get(
        "invocationRegion"
    ) != judges[1].get("invocationRegion"):
        raise ValueError("Comparison requires the same judge or explicit cross-judging")
    rows = []
    for bundle in (left, right):
        manifest, report = bundle["manifest"], bundle["summary"]
        role = manifest.get("targetRole", "target")
        target = manifest["bedrock"][role]
        calibrated = report.get("calibration", {}).get("trusted") is True
        rows.append(
            dict(
                target=target.get("invocationId", target.get("profileId")),
                targetRegion=manifest.get("targetInvocationRegion"),
                repeats=manifest["repeats"],
                outcome=manifest.get("outcome"),
                judgeCalibrated=calibrated,
                judge=manifest["bedrock"][manifest.get("judgeRole", "judge")][
                    "invocationId"
                ],
                judgeCalibration=report.get("calibration"),
                headline=(
                    report["headline"]
                    if calibrated
                    or report.get("headlineMetricBasis") == "deterministic"
                    else None
                ),
                categories=(
                    report["categories"]
                    if calibrated
                    or report.get("headlineMetricBasis") == "deterministic"
                    else None
                ),
                tasks=report["tasks"],
                returnedTokens=report["tokens"],
                tokenCostUsd=report["calibration_and_task_estimated_cost_usd"],
                usageIncomplete=report.get("usage_incomplete", False),
                capabilityGaps=report.get("capability_gaps", []),
                pass3Note=(
                    "Not measured with one repetition"
                    if manifest["repeats"] == 1
                    else "Three repetitions"
                ),
            )
        )
    return dict(
        status="provisional; human review pending",
        judges=ids(judges),
        crossJudging=cross,
        results=rows,
        caveats=(
            [
                "Targets were assessed by different judges. Deterministic task success and leaks are the headline; calibrated free-text verdicts are secondary and human review is pending."
            ]
            if cross
            else (["Different independent judges were proposed; only calibrated verdicts are usable. Deterministic checks remain the headline."] if first.get("independentJudging") else ["A common judge was used; human review remains pending."])
        ),
    )


def load(directory):
    return {
        name: json.loads((directory / (name + ".json")).read_text())
        for name in ("manifest", "summary")
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("primary", type=Path)
    parser.add_argument("control", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = comparison(load(args.primary), load(args.control))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
