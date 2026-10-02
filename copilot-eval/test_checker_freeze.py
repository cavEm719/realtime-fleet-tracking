"""Verify the recorded freeze and supplied review counts; never tune or regrade answers."""
import hashlib
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


class CheckerFreezeTest(unittest.TestCase):
    def setUp(self):
        self.freeze = json.loads((ROOT / "copilot-eval/checker-freeze.json").read_text())
        self.review = json.loads((ROOT / "docs/review/round3-independent-review.json").read_text())

    def test_checker_and_review_files_are_frozen_byte_for_byte(self):
        self.assertEqual("FROZEN", self.freeze["status"])
        self.assertEqual("review-v2", self.freeze["checkerVersion"])
        actual = hashlib.sha256((ROOT / self.freeze["checkerFile"]).read_bytes()).hexdigest()
        self.assertEqual(self.freeze["checkerSha256"], actual)
        for path, expected in self.freeze["reviewFiles"].items():
            self.assertEqual(expected, hashlib.sha256((ROOT / path).read_bytes()).hexdigest())

    def test_failure_review_and_success_sample_counts_match_unchanged_labels(self):
        totals = {"failures": 0, "falseFails": 0, "successes": 0, "falsePasses": 0}
        for alias, item in self.review.items():
            rows, summary = item["runs"], item["summary"]
            failures = [r for r in rows if not r["checker"]]
            successes = [r for r in rows if r["checker"]]
            false_fails = sum(r["reviewSolved"] for r in failures)
            false_passes = sum(not r["reviewSolved"] for r in successes)
            self.assertEqual(120 - summary["checkerSolved"], len(failures))
            self.assertEqual(summary["checkerFailuresReviewed"], len(failures))
            self.assertEqual(summary["checkerFalseFails"], false_fails)
            self.assertEqual(summary["sampledCheckerSuccesses"], len(successes))
            self.assertEqual(0, false_passes)
            self.assertEqual(summary["reviewedSolvedEstimate"], summary["checkerSolved"] + false_fails)
            totals["failures"] += len(failures)
            totals["falseFails"] += false_fails
            totals["successes"] += len(successes)
            totals["falsePasses"] += false_passes
        self.assertEqual({"failures": 18, "falseFails": 10, "successes": 53, "falsePasses": 0}, totals)
        self.assertEqual(4, len(self.freeze["knownFalseFailPatterns"]))
        self.assertEqual(10, sum(p["runs"] for p in self.freeze["knownFalseFailPatterns"]))

    def test_ledger_keeps_originals_and_does_not_invent_round_one_review_scores(self):
        scores = self.freeze["scores"]
        self.assertEqual(6, len(scores))
        self.assertTrue(all(r["reviewCorrectedSolved"] is None for r in scores if r["round"] == 1))
        expected = {("DeepSeek-V3.1", 1): (57, 73, None), ("gpt-oss-120b", 1): (13, 15, None),
                    ("DeepSeek-V3.1", 2): (59, 113, 113), ("gpt-oss-120b", 2): (45, 98, 98),
                    ("DeepSeek-V3.1", 3): (106, 109, 114), ("gpt-oss-120b", 3): (113, 113, 118)}
        for row in scores:
            self.assertEqual(expected[row["model"], row["round"]],
                             (row["originalCheckerSolved"], row["frozenCheckerSolved"], row["reviewCorrectedSolved"]))
        self.assertIn("in-sample", self.freeze["round2Validation"]["method"])


if __name__ == "__main__":
    unittest.main()
