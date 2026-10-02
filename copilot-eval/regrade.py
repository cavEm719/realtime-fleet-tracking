#!/usr/bin/env python3
"""Recheck retained answers offline; preserve original evidence and scoring changes."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
from bedrock import write_report
from graders import row_data_leaks, grade, leaks
from ground_truth import truth
from timestamp_audit import audit as timestamp_audit


def regrade(row):
    updated = copy.deepcopy(row)
    updated["timestampAudit"] = timestamp_audit(row)
    updated["data_leaks"] = row_data_leaks(row)
    updated["originalGrading"] = {
        key: row.get(key)
        for key in ("task_success", "deterministic_errors", "leaks", "side_effect_pass")
    }
    if row.get("rules_before") is None or row.get("rules_after") is None:
        return updated
    updated.update(
        grade(
            row["task"],
            row["ground_truth"],
            row.get("answer", ""),
            row.get("tool_evidence", []),
            row["rules_before"],
            row["rules_after"],
        )
    )
    every_turn_leaks = []
    if row.get("responses"):
        for response in row["responses"]:
            scoped = truth(
                dict(
                    row["task"],
                    tenantId=response["tenantId"],
                    query={"kind": "refusal"},
                )
            )
            reply = response["reply"]
            every_turn_leaks.extend(
                leaks(
                    reply.get("answer", ""),
                    (reply.get("evaluation") or {}).get("tools", []),
                    scoped,
                )
            )
        for reply in row.get("setup_responses", []):
            every_turn_leaks.extend(
                leaks(
                    reply.get("answer", ""),
                    (reply.get("evaluation") or {}).get("tools", []),
                    row["ground_truth"],
                )
            )
    else:
        every_turn_leaks.extend(row.get("leaks", []))
    updated["leaks"] = sorted(set(updated["leaks"] + every_turn_leaks))
    updated["task_success"] = (
        updated["task_success"]
        and not updated["data_leaks"]
        and not (not row.get("responses") and row.get("leaks"))
        and not any(
            row.get(key)
            for key in (
                "error",
                "restore_error",
                "side_effect_error",
            )
        )
    )
    if isinstance(row.get("judge"), dict):
        updated["judge_adjusted_success"] = (
            updated["task_success"] and row["judge"]["faithful"]
        )
        if row["task"]["query"]["kind"] == "clarification":
            updated["judge_adjusted_success"] = (
                updated["judge_adjusted_success"]
                and row["judge"].get("clarificationAcceptable") is True
            )
    return updated


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    raw = (args.source / "evaluations.jsonl").read_bytes()
    rows = [regrade(json.loads(line)) for line in raw.decode().splitlines()]
    manifest = json.loads((args.source / "manifest.json").read_text())
    manifest.update(
        regradedOffline=True,
        originalEvaluationsSha256=hashlib.sha256(raw).hexdigest(),
        graderSha256=hashlib.sha256(
            Path(__file__).with_name("graders.py").read_bytes()
        ).hexdigest(),
        regradeReason="Apply the reviewed fact-based checker uniformly, with strict identifier echoes separate from data leakage; all recorded turns rechecked, original scores retained, no model reruns.",
    )
    calibration = json.loads((args.source / "calibration.json").read_text())
    usage = json.loads((args.source / "judge-usage.json").read_text())
    prices = json.loads((args.source / "summary.json").read_text())[
        "price_usd_per_million"
    ]
    (args.output / "evaluations.jsonl").write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows)
    )
    for filename, value in (
        ("manifest.json", manifest),
        ("calibration.json", calibration),
        ("judge-usage.json", usage),
    ):
        (args.output / filename).write_text(json.dumps(value, indent=2) + "\n")
    write_report(args.output, rows, prices, calibration, usage, manifest)
