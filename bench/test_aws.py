#!/usr/bin/env python3
"""Offline tests: histogram aggregation and evaluation checks; no network/model calls."""
import importlib.util
import tempfile
import unittest
import subprocess
from unittest.mock import patch
from types import SimpleNamespace
from pathlib import Path


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


ROOT = Path(__file__).resolve().parents[1]
aws = module("awsbench", ROOT / "bench/aws.py")
eval = module("bedrockeval", ROOT / "copilot-eval/bedrock.py")
report = module("awsreport", ROOT / "bench/report-aws.py")


class AwsToolingTest(unittest.TestCase):
    def test_collection_failure_preserves_primary_error_and_all_collection_errors(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            (out / "status.json").write_text(
                '{"outcome":"failed","error":"Generator had failed connections"}'
            )
            aws.record_collection_errors(out, ["Missing histogram"])
            aws.record_collection_errors(out, ["Copy failed", "Missing histogram"])
            import json

            status = json.loads((out / "status.json").read_text())
            self.assertEqual(status["error"], "Generator had failed connections")
            self.assertEqual(status["outcome"], "incomplete")
            self.assertEqual(
                status["collection_errors"], ["Missing histogram", "Copy failed"]
            )

    def test_missing_gateway_scrapes_do_not_manufacture_zero_frame_rate(self):
        evidence = {
            "hosts": {
                "gateway-1": {
                    "sampledCounterRates": {"gateway_frames_sent_total": 100}
                },
                "gateway-2": {},
            }
        }
        self.assertEqual(report.gateway_frame_rate(evidence, ["gateway-1"]), 100)
        self.assertIsNone(
            report.gateway_frame_rate(evidence, ["gateway-1", "gateway-2"])
        )
        evidence["hosts"]["gateway-2"]["sampledCounterRates"] = {
            "gateway_frames_sent_total": None
        }
        self.assertIsNone(
            report.gateway_frame_rate(evidence, ["gateway-1", "gateway-2"])
        )

    def test_histograms_merge_by_count_not_average_of_percentiles(self):
        with tempfile.TemporaryDirectory() as directory:
            a = Path(directory) / "a.csv"
            b = Path(directory) / "b.csv"
            a.write_text("latency_ms,count\n1,99\n")
            b.write_text("latency_ms,count\n1000,1\n")
            result = aws.merge_histograms([a, b])
            self.assertEqual(result["p99Ms"], 1)
            self.assertEqual(result["samples"], 100)

    def test_missing_samples_do_not_manufacture_zero_latency(self):
        self.assertEqual(aws.merge_histograms([])["p99Ms"], None)

    def test_clock_sync_is_required_and_offsets_are_measured(self):
        self.assertAlmostEqual(
            aws.clock_offset(
                "System time : 0.000123 seconds slow of NTP time\nLeap status : Normal"
            ),
            0.123,
        )
        with self.assertRaises(ValueError):
            aws.clock_offset(
                "System time : 0.1 seconds fast of NTP time\nLeap status : Not synchronised"
            )

    def test_deployment_commands_parse_and_do_not_archive_terraform_state(self):
        deploy = module("deploytest", ROOT / "infra/aws/scripts/deploy.py")
        roles = {
            name: name
            for name in ["kafka", "redis", "mysql", "api", "copilot", "nginx"]
        }
        roles.update(
            {"processor-1": "processor", "gateway-1": "gateway", "loadgen-1": "loadgen"}
        )
        inventory = {
            "project": "fleet-bench",
            "region": "us-east-1",
            "hosts": {
                name: {
                    "public_ip": "203.0.113.10",
                    "private_ip": "10.42.1.10",
                    "role": role,
                }
                for name, role in roles.items()
            },
            "images": {
                name: "000000000000.dkr.ecr.us-east-1.amazonaws.com/fleet-bench/" + name
                for name in [
                    "api",
                    "copilot",
                    "processor",
                    "gateway",
                    "replayer",
                    "loadtest",
                ]
            },
        }
        commands = []

        def capture(host, key, command, **kwargs):
            commands.append(command)
            return SimpleNamespace(stdout="")

        with tempfile.TemporaryDirectory() as folder:
            (Path(folder) / "infra/aws").mkdir(parents=True)
            with patch.object(deploy, "ROOT", Path(folder)), patch.object(
                deploy, "ssh", capture
            ), patch.object(
                deploy.subprocess, "run", return_value=SimpleNamespace(stdout=b"")
            ) as tar:
                deploy.deploy(
                    inventory,
                    Path("private-key"),
                    "test-tag",
                    "fake-demo-password",
                    "",
                    False,
                )
                self.assertIn("--exclude=*.tfstate*", tar.call_args.args[0])
                self.assertIn("--exclude=.terraform", tar.call_args.args[0])
        for command in commands:
            subprocess.run(
                ["bash", "-n"],
                input=command,
                text=True,
                check=True,
                capture_output=True,
            )
        self.assertTrue(
            any(
                "KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://10.42.1.10:9092" in command
                for command in commands
            )
        )

    def test_arguments_check_actual_values(self):
        q = {"expectedArguments": {"getAssetStatus": {"assetId": 366000000}}}
        self.assertTrue(
            eval.arguments_ok(
                q, [{"tool": "getAssetStatus", "arguments": '{"assetId":366000000}'}]
            )
        )
        self.assertFalse(
            eval.arguments_ok(
                q, [{"tool": "getAssetStatus", "arguments": '{"assetId":366000001}'}]
            )
        )

    def test_manual_pending_is_not_counted_as_reviewed(self):
        rows = [
            {
                "category": "faithfulness",
                "automatic_pass": True,
                "manual": "PENDING",
                "input_tokens": 1000,
                "output_tokens": 500,
            }
        ]
        summary = eval.summarize(rows, [1, 2, 3, 4])
        self.assertEqual(summary["categories"]["faithfulness"]["manual_reviewed"], 0)
        self.assertIsNone(summary["categories"]["faithfulness"]["manual_pass_rate"])
        self.assertAlmostEqual(summary["estimated_token_cost_usd"], 0.002)


if __name__ == "__main__":
    unittest.main()
