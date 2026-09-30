import json
import unittest
from pathlib import Path
from query_basis import bounds, canonical, check

HERE = Path(__file__).resolve().parent
NOW = "2024-01-08T12:00:00Z"


class QueryBasisTest(unittest.TestCase):
    def basis_text(self):
        fixture = json.loads((HERE / "fixture.json").read_text())
        names = [f["name"] for f in fixture["fences"] if f["id"] in {1, 4}]
        return ", ".join(names) + " · Events: arrivals (ENTER) · Time: 2024-01-07T00:00:00Z .. 2024-01-07T23:59:59.999Z (inclusive UTC) · Metric: event count · Group by: port"

    def reply(self):
        args = {"spec": {"metric": "event_count", "eventType": "ENTER", "ports": [4, 1, 1],
                         "timeWindow": "yesterday", "groupBy": "port"}}
        return {"answer": "Query basis\nScope: Los Angeles / Seattle",
                "queryBasis": [{"tool": "queryEvents", "arguments": canonical("queryEvents", args, NOW),
                                "text": self.basis_text(), "results": [], "assumptions": []}],
                "evaluation": {"utcNow": NOW, "tools": [{"tool": "queryEvents", "arguments": json.dumps(args),
                                                        "result": '{"total": 7}'}]}}

    def test_matches_canonical_executed_spec_with_database_names(self):
        self.assertEqual([], check(self.reply(), 1, json.loads((HERE / "fixture.json").read_text())))

    def test_rejects_missing_basis_changed_arguments_and_foreign_names(self):
        fixture = json.loads((HERE / "fixture.json").read_text())
        missing = self.reply()
        del missing["queryBasis"]
        self.assertIn("missing query basis", check(missing, 1, fixture))
        altered = self.reply()
        altered["queryBasis"][0]["arguments"]["eventType"] = "EXIT"
        self.assertTrue(check(altered, 1, fixture))
        wrong_name = self.reply()
        wrong_name["queryBasis"][0]["text"] = "Houston"
        self.assertTrue(check(wrong_name, 1, fixture))
        self.assertTrue(check(self.reply(), 2, fixture))

    def test_skipped_or_invalid_calls_have_no_successful_query_basis(self):
        reply = {"answer": "Query basis\nNo successful query.", "queryBasis": [],
                 "evaluation": {"utcNow": NOW, "tools": [{"tool": "queryEvents", "arguments": "{}",
                                                           "result": '{"error": "Invalid arguments"}'}]}}
        self.assertEqual([], check(reply, 1, {}))

    def test_new_suite_truth_is_computed_only_from_unchanged_fixture(self):
        from query_tasks import build
        tasks = build()
        self.assertEqual(8, len(tasks))
        self.assertEqual({"event_count", "distinct_vessels"}, {r["spec"]["metric"] for r in tasks})
        self.assertTrue(all(r["suiteVersion"] == "query-transparency-v1" for r in tasks))
        self.assertEqual(tasks, json.loads((HERE / "query-transparency-v1.json").read_text()))

    def test_manual_runner_uses_injected_transport_and_reports_spec_mismatch(self):
        from run_query_suite import run
        from query_tasks import build
        fixture = json.loads((HERE / "fixture.json").read_text())
        observed = []
        def fake_send(url, body):
            observed.append((url, body))
            return self.reply()
        rows = run("http://test.invalid", build()[:1], fixture, fake_send)
        self.assertEqual(1, len(observed))
        self.assertEqual("How many ships arrived today?", observed[0][1]["message"])
        self.assertTrue(rows[0]["toolDiagnostics"]["genericQueryUsed"])
        self.assertFalse(rows[0]["toolDiagnostics"]["requestedSpecMatched"])
        self.assertFalse(rows[0]["toolDiagnostics"]["referenceTotalMatched"])

    def test_week_and_today_have_explicit_utc_boundaries(self):
        self.assertEqual((1704672000000, 1704715200000), bounds("thisWeek", NOW))
        self.assertEqual(bounds("today", NOW), bounds("thisWeek", NOW))
        self.assertEqual({"limit": "50"}, canonical("listFences", {}, NOW))


if __name__ == "__main__":
    unittest.main()
