"""Offline checks for aligned resource evidence and missing/reset counters."""

import json
import tempfile
import unittest
from pathlib import Path
import resource_evidence as evidence


class ResourceEvidenceTest(unittest.TestCase):
    def test_missing_and_reset_counters_are_not_zero(self):
        self.assertIsNone(evidence.counter_delta([], lambda r: r, ["count"]))
        self.assertEqual(
            evidence.counter_delta(
                [{"count": 10}, {"count": 2}], lambda r: r, ["count"]
            ),
            {"count": None},
        )

    def test_reset_in_middle_is_unknown_even_when_final_counter_is_higher(self):
        rows = [{"count": value} for value in [1, 30, 10, 50]]
        self.assertEqual(
            evidence.counter_delta(rows, lambda r: r, ["count"]), {"count": None}
        )

    def test_lua_commandstats_reset_does_not_report_a_false_total(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "redis"
            folder.mkdir()
            rows = [
                {
                    "timeMs": i * 1000,
                    "network": "",
                    "redisInfo": f"used_cpu_sys:{i}\nused_cpu_user:{i}\ncmdstat_evalsha:calls={count},usec={count * 100}\n",
                }
                for i, count in enumerate([1, 30, 10, 50], 1)
            ]
            (folder / "host.jsonl").write_text("\n".join(map(json.dumps, rows)))
            result = evidence.host_evidence(folder, 1000, 4000)
            self.assertEqual(
                result["redis"], {"error": "Lua counters reset during measurement"}
            )

    def test_redis_commandstats_parser(self):
        result = evidence.redis_info(
            "used_cpu_user:2.5\ncmdstat_evalsha:calls=10,usec=400,usec_per_call=40\n"
        )
        self.assertEqual(result["cmdstat_evalsha.usec"], 400)
        self.assertEqual(result["used_cpu_user"], 2.5)

    def test_measurement_excludes_startup_and_normalizes_container_cpu(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "gateway-1"
            folder.mkdir()
            (Path(temp) / "gateway-1-before.txt").write_text("CPU(s): 2\n")
            rows = [
                {
                    "timeMs": t,
                    "hostCpuPercent": cpu,
                    "network": "ens5: 0 0 0 0 0 0 0 0 0 0 0 0",
                }
                for t, cpu in [(0, 100), (1000, 20), (2000, 40)]
            ]
            (folder / "host.jsonl").write_text("\n".join(map(json.dumps, rows)))
            containers = [
                {"timeMs": t, "Name": "gateway", "CPUPerc": cpu, "MemPerc": "25%"}
                for t, cpu in [(0, "200%"), (1000, "60%"), (2000, "100%")]
            ]
            (folder / "docker-stats.jsonl").write_text(
                "\n".join(map(json.dumps, containers))
            )
            result = evidence.host_evidence(folder, 1000, 2000)
            self.assertEqual(result["hostCpuPercent"]["mean"], 30)
            self.assertEqual(
                result["containers"]["gateway"]["hostEquivalentCpuPercent"]["mean"], 40
            )
            self.assertEqual(result["containerWindow"], "measurement")
            self.assertIsNone(result["networkAllowanceExceededDeltas"])

    def test_sampled_counter_rate_uses_its_own_elapsed_window(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "gateway-1"
            folder.mkdir()
            rows = [
                {
                    "timeMs": 1000,
                    "hostCpuPercent": 20,
                    "network": "",
                    "prometheus": "gateway_frames_sent_total 10\n",
                },
                {
                    "timeMs": 3000,
                    "hostCpuPercent": 30,
                    "network": "",
                    "prometheus": "gateway_frames_sent_total 50\n",
                },
            ]
            (folder / "host.jsonl").write_text("\n".join(map(json.dumps, rows)))
            result = evidence.host_evidence(folder, 0, 4000)
            self.assertEqual(
                result["sampledCounterRates"]["gateway_frames_sent_total"], 20
            )
            self.assertEqual(result["counterRateWindowSeconds"], 2)

    def test_legacy_container_window_is_explicit(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / "gateway-1"
            folder.mkdir()
            (folder / "host.jsonl").write_text("")
            (folder / "docker-stats.jsonl").write_text(
                json.dumps({"Name": "gateway", "CPUPerc": "180%"})
                + "\n"
                + json.dumps({"error": "Docker reader failed"})
            )
            result = evidence.host_evidence(folder, 1000, 2000)
            self.assertIn("lack timestamps", result["containerWindow"])
            self.assertEqual(result["containerSampleErrors"], 1)
            self.assertIsNone(result["hostCpuPercent"])


if __name__ == "__main__":
    unittest.main()
