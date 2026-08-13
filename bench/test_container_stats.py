import importlib.util
from pathlib import Path
import io
import json
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "sample", Path(__file__).resolve().parents[1] / "infra/aws/scripts/sample.py"
)
sample = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sample)


class ContainerStatsTest(unittest.TestCase):
    def test_timestamped_container_cpu_strips_terminal_codes(self):
        output = io.StringIO()
        with patch.object(sample.time, "time", return_value=100.25):
            sample.record_container_stats(
                io.StringIO('\x1b[2J\x1b[H{"Name":"fleet","CPUPerc":"150.0%"}\n'),
                output,
            )
        self.assertEqual(
            json.loads(output.getvalue()),
            dict(Name="fleet", CPUPerc="150.0%", timeMs=100250),
        )

    def test_docker_failure_is_evidence_not_discarded(self):
        output = io.StringIO()
        sample.record_container_stats(io.StringIO("permission denied\n"), output)
        self.assertEqual(json.loads(output.getvalue())["error"], "permission denied")


class NetworkLimitsTest(unittest.TestCase):
    def test_ena_shaping_counters_and_available_connections(self):
        raw = "NIC statistics:\n bw_out_allowance_exceeded: 7\n conntrack_allowance_available: 123\n rx_bytes: 999\n"
        self.assertEqual(
            sample.parse_network_limits(raw),
            {
                "bw_out_allowance_exceeded": 7,
                "conntrack_allowance_available": 123,
            },
        )

    def test_unsupported_statistics_are_not_invented_as_zero(self):
        self.assertEqual(sample.parse_network_limits("not supported"), {})
