"""Local model selection, pilots and comparison checks; no model or AWS calls."""

import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from bedrock import select_tasks, validate_selection, summarize, write_report
from compare import comparison
from estimate import estimate
from hooks import FleetHooks
from unittest.mock import Mock
import hashlib

CATALOG = json.loads(
    (HERE.parent / "bench/results/AWS/bedrock-eval-1/manifest.json").read_text()
)
TASKS = json.loads((HERE / "questions-aws.json").read_text())
MODELS = CATALOG["bedrock"]


class ModelPlanTest(unittest.TestCase):
    def test_offline_regrade_preserves_original_and_runtime_failures(self):
        from regrade import regrade
        from ground_truth import truth

        task = next(t for t in TASKS if t["id"] == "beacon-status")
        row = dict(
            task=task,
            ground_truth=truth(task),
            answer="MMSI 366000001 is outside Seattle. The vessel appears nearby.",
            tool_evidence=[],
            rules_before=[],
            rules_after=[],
            task_success=False,
            deterministic_errors=["unrecognized vessel name appears"],
            leaks=[],
        )
        revised = regrade(row)
        self.assertTrue(revised["task_success"])
        self.assertFalse(revised["originalGrading"]["task_success"])
        self.assertFalse(row["task_success"])
        row["error"] = "Request failed after partial answer"
        self.assertFalse(regrade(row)["task_success"])
        row.pop("error")
        row["leaks"] = ["foreign asset from earlier turn"]
        self.assertFalse(regrade(row)["task_success"])

    def test_regrade_rechecks_all_turns_and_keeps_actual_foreign_echoes(self):
        from regrade import regrade
        from ground_truth import truth

        task = next(t for t in TASKS if t["id"] == "beacon-status")
        row = dict(
            task=task,
            ground_truth=truth(task),
            answer="MMSI 366000001 is outside Seattle.",
            tool_evidence=[],
            rules_before=[],
            rules_after=[],
            task_success=False,
            leaks=["foreign fence 2"],
            responses=[
                {
                    "tenantId": 1,
                    "reply": {
                        "answer": "222 meters from the port fence\n2. Another vessel.",
                        "evaluation": {"tools": []},
                    },
                }
            ],
        )
        fixed = regrade(row)
        self.assertFalse(fixed["leaks"])
        self.assertEqual(fixed["originalGrading"]["leaks"], ["foreign fence 2"])
        row["responses"].insert(
            0,
            {
                "tenantId": 1,
                "reply": {
                    "answer": "I cannot access 366000100.",
                    "evaluation": {"tools": []},
                },
            },
        )
        self.assertIn("foreign asset 366000100", regrade(row)["leaks"])

    def test_resume_retains_checkpoint_and_known_format_usage(self):
        from bedrock import resume_evidence

        manifest = dict(
            fixtureSha256="fixture",
            fixedNow="clock",
            rubricSha256="rubric",
            targetRole="target",
            judgeRole="judge",
            bedrock={},
            plannedTasks=[],
            repeats=3,
            outcome="Unknown judge usage",
        )
        row = dict(
            id="test",
            trial=1,
            task={"turns": ["question"]},
            answer="answer",
            responses=[{}],
            usage_incomplete=True,
            judge_error="Judge must submit exactly one verdict tool call",
            judge_input_tokens=10,
            judge_output_tokens=1024,
        )
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            originals = {
                "manifest.json": manifest,
                "summary.json": {"price_usd_per_million": [0.58, 1.68, 0.15, 0.6]},
                "judge-usage.json": {"input": 10, "output": 1024},
                "calibration.json": {"trusted": True},
            }
            for filename, value in originals.items():
                (directory / filename).write_text(json.dumps(value))
            raw = json.dumps(row) + "\n"
            (directory / "evaluations.jsonl").write_text(raw)
            (directory / "judge-calls.jsonl").write_text(
                json.dumps(
                    {
                        "request": {"question": "question", "answer": "answer"},
                        "response": {
                            "usage": {"inputTokens": 10, "outputTokens": 1024}
                        },
                    }
                )
                + "\n"
            )
            rows, usage, checkpoint = resume_evidence(
                directory, manifest, [0.58, 1.68, 0.15, 0.6]
            )
            self.assertFalse(rows[0]["usage_incomplete"])
            self.assertTrue(rows[0]["originalUsageIncomplete"])
            self.assertEqual(usage, {"input": 10, "output": 1024})
            self.assertEqual(
                (directory / checkpoint["directory"] / "evaluations.jsonl").read_text(),
                raw,
            )
            rows[0]["judge_error"] = "Transport error: no returned usage"
            rows[0]["usage_incomplete"] = True
            (directory / "evaluations.jsonl").write_text(json.dumps(rows[0]) + "\n")
            with self.assertRaises(ValueError):
                resume_evidence(directory, manifest, [0.58, 1.68, 0.15, 0.6])
            rows[0]["error"] = "Model call failed"
            rows[0]["responses"] = [{"reply": {"evaluation": {"attempts": [
                {"call": 1, "attempt": 1, "success": False, "usageUnknown": True}
            ]}}}]
            (directory / "evaluations.jsonl").write_text(json.dumps(rows[0]) + "\n")
            (directory / "judge-calls.jsonl").unlink()
            retained, _, _ = resume_evidence(directory, manifest, [0.58, 1.68, 0.15, 0.6])
            self.assertTrue(retained[0]["usage_incomplete"])
            from bedrock import unknown_attempts
            self.assertEqual(unknown_attempts(retained), 1)
            retained[0]["responses"][0]["reply"]["evaluation"]["attempts"][0]["error"] = "ValidationException"
            self.assertEqual(unknown_attempts(retained), 0)

    def test_both_judges_force_the_only_tool_with_strict_schema(self):
        from bedrock import verdict_request, VERDICT_SCHEMA

        for model in ("deepseek.v3-v1:0", "openai.gpt-oss-120b-1:0"):
            request = verdict_request(model, {"answer": "test"})
            self.assertEqual(request["toolConfig"]["toolChoice"], {"any": {}})
            tools = request["toolConfig"]["tools"]
            self.assertEqual(len(tools), 1)
            spec = tools[0]["toolSpec"]
            self.assertEqual(spec["name"], "submit_verdict")
            self.assertTrue(spec["strict"])
            self.assertEqual(spec["inputSchema"]["json"], VERDICT_SCHEMA)

    def test_direct_regional_target_and_claude_profile_are_valid(self):
        self.assertEqual(
            validate_selection(MODELS, "target", "deepseek.v3-v1:0", [0.58, 1.68]),
            "us-west-2",
        )
        self.assertEqual(
            validate_selection(
                MODELS, "comparison", MODELS["comparison"]["invocationId"], [1.1, 5.5]
            ),
            "us-east-1",
        )

    def test_wrong_prices_or_non_us_regions_are_rejected(self):
        with self.assertRaises(ValueError):
            validate_selection(MODELS, "target", "deepseek.v3-v1:0", [3.3, 16.5])
        bad = copy.deepcopy(MODELS)
        bad["target"]["destinationRegions"] = ["eu-west-2"]
        with self.assertRaises(ValueError):
            validate_selection(bad, "target", "deepseek.v3-v1:0", [0.58, 1.68])
        bad = copy.deepcopy(MODELS)
        bad["target"]["destinationRegions"] = ["us-east-1", "us-west-2"]
        with self.assertRaises(ValueError):
            validate_selection(bad, "target", "deepseek.v3-v1:0", [0.58, 1.68])

    def test_pilot_subset_keeps_full_calibration_references(self):
        from calibration import CASES, run_calibration

        selected = select_tasks(
            TASKS, "pilot", 1, ["atlas-status", "la-yesterday", "draft-la"]
        )
        self.assertEqual(len(selected), 3)
        labels = {answer: good for _, _, answer, good in CASES}
        calls = []

        def judge(payload):
            calls.append(payload)
            return {"faithful": labels[payload["answer"]]}

        result = run_calibration(TASKS, judge)
        self.assertTrue(result["trusted"])
        self.assertEqual(len(calls), 14)
        self.assertTrue(
            any("FOREIGN_T2_CANARY_Atlas" in call["answer"] for call in calls)
        )

    def test_pilot_is_exactly_three_tasks_once_and_full_is_unfiltered(self):
        ids = ["atlas-status", "la-yesterday", "draft-la"]
        self.assertEqual(len(select_tasks(TASKS, "pilot", 1, ids)), 3)
        for repeats, selected in [(3, ids), (1, ids[:2]), (1, ids[:2] + ["missing"])]:
            with self.assertRaises(ValueError):
                select_tasks(TASKS, "pilot", repeats, selected)
        with self.assertRaises(ValueError):
            select_tasks(TASKS, "full", 3, ids)
        self.assertEqual(select_tasks(TASKS, "full", 3, None), TASKS)

    def test_one_repetition_does_not_claim_pass_cubed(self):
        row = dict(id="atlas-status", category="lookup", task_success=True)
        summary = summarize([row], [0.58, 1.68, 3.3, 16.5], 1)
        self.assertEqual(summary["tasks"]["atlas-status"]["pass_count"], 1)
        self.assertIsNone(summary["tasks"]["atlas-status"]["pass3"])
        self.assertIsNone(summary["categories"]["lookup"]["pass3_rate"])

    def test_manual_template_has_only_planned_pilot_repetitions(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            write_report(
                out,
                [],
                [0.58, 1.68, 3.3, 16.5],
                {"trusted": False},
                {"input": 0, "output": 0},
                {"repeats": 1, "purpose": "pilot", "plannedTasks": TASKS[:3]},
            )
            template = json.loads((out / "manual-review.json").read_text())
            self.assertEqual(len(template), 3)
            self.assertTrue(all(v["verdict"] == "PENDING" for v in template.values()))

    def bundle(self, role="target", repeats=3):
        row = dict(
            id="atlas-status",
            category="lookup",
            task_success=True,
            input_tokens=100,
            output_tokens=20,
        )
        summary = summarize([row], [0.58, 1.68, 3.3, 16.5], repeats)
        summary.update(
            calibration={"trusted": True}, calibration_and_task_estimated_cost_usd=0.01
        )
        return dict(
            manifest=dict(
                purpose="full",
                fixtureSha256="same",
                fixedNow="2024-01-08T12:00:00Z",
                plannedTasks=TASKS,
                bedrock=MODELS,
                repeats=repeats,
                targetRole=role,
                targetInvocationRegion=MODELS[role]["invocationRegion"],
            ),
            summary=summary,
        )

    def test_comparison_keeps_one_run_limit_and_requires_same_judge(self):
        left, right = self.bundle(), self.bundle("comparison", 1)
        report = comparison(left, right)
        self.assertEqual(report["results"][1]["repeats"], 1)
        self.assertIn("Not measured", report["results"][1]["pass3Note"])
        bad = copy.deepcopy(right)
        bad["manifest"]["bedrock"]["judge"]["invocationId"] = "different-judge"
        with self.assertRaises(ValueError):
            comparison(left, bad)

    def test_comparison_rejects_pilot_and_suppresses_uncalibrated_verdicts(self):
        left, right = self.bundle(), self.bundle("comparison")
        right["summary"]["calibration"]["trusted"] = False
        self.assertIsNone(comparison(left, right)["results"][1]["headline"])
        right["manifest"]["purpose"] = "pilot"
        with self.assertRaises(ValueError):
            comparison(left, right)

    def test_deployed_target_region_is_checked_without_full_environment_output(self):
        hooks = object.__new__(FleetHooks)
        hooks.model = "deepseek.v3-v1:0"
        hooks.region = "us-west-2"
        hooks.hosts = {}

        def command(name, cmd):
            if cmd.startswith("sha256sum"):
                file = Path(cmd.split()[-1]).name
                return hashlib.sha256((HERE / file).read_bytes()).hexdigest()
            values = {
                "COPILOT_FIXED_NOW": "2024-01-08T12:00:00Z",
                "COPILOT_MODEL": hooks.model,
                "AWS_REGION": hooks.region,
                "COPILOT_EVALUATION_ENABLED": "true",
            }
            for key, value in values.items():
                if "$" + key + '"' in cmd:
                    return value
            self.fail("Unexpected command")

        hooks.command = Mock(side_effect=command)
        hooks.validate()
        self.assertTrue(
            any('$AWS_REGION"' in c.args[1] for c in hooks.command.call_args_list)
        )

    def test_cross_judging_preserves_deterministic_headline_when_judge_fails(self):
        left, right = self.bundle(), self.bundle("comparison")
        from bedrock import parse_verdict

        cross = json.loads((HERE / "models-cross.json").read_text())["bedrock"]
        for bundle, role in [(left, "judge"), (right, "comparisonJudge")]:
            bundle["manifest"].update(bedrock=cross, crossJudging=True, judgeRole=role)
            bundle["summary"]["headlineMetricBasis"] = "deterministic"
            bundle["summary"]["calibration"] = {"trusted": False, "accuracy": 0.8}
        result = comparison(left, right)
        self.assertTrue(result["crossJudging"])
        self.assertIsNotNone(result["results"][0]["headline"])
        bad = copy.deepcopy(right)
        bad["manifest"]["judgeRole"] = "judge"
        with self.assertRaises(ValueError):
            comparison(left, bad)
        valid = {
            "faithful": False,
            "clarificationAcceptable": True,
            "reason": "Fabricated",
        }
        response = {
            "output": {
                "message": {
                    "content": [{"toolUse": {"name": "submit_verdict", "input": valid}}]
                }
            }
        }
        self.assertEqual(parse_verdict(response), valid)
        response["output"]["message"]["content"][0]["toolUse"]["input"][
            "faithful"
        ] = "true"
        with self.assertRaises(ValueError):
            parse_verdict(response)

    def test_planning_cost_includes_both_pilots_and_all_calibrations(self):
        value = estimate()
        self.assertGreater(value["twoPilotsUsd"], 0)
        self.assertLess(value["combinedHostAndInferenceEstimateUsd"], 12)
        self.assertLess(
            value["oneRunComparisonTotalUsd"], value["threeRunComparisonTotalUsd"]
        )


if __name__ == "__main__":
    unittest.main()
