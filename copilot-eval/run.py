#!/usr/bin/env python3
"""Manual real-model tool-selection check; never run by Maven."""
import argparse
import json
import time
import urllib.error
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:8083")
    parser.add_argument("--questions", type=Path, default=Path(__file__).with_name("questions.json"))
    parser.add_argument("--delay", type=float, default=10,
                        help="Seconds between questions to respect per-model-call LLM quotas")
    parser.add_argument("--timeout", type=float, default=40)
    args = parser.parse_args()
    if args.delay < 0 or args.timeout <= 0:
        parser.error("delay must be nonnegative and timeout positive")
    questions = json.loads(args.questions.read_text())
    passed = 0
    for index, question in enumerate(questions):
        if index:
            time.sleep(args.delay)
        payload = json.dumps({"tenantId": question["tenantId"], "userId": "manual-eval",
                              "message": question["question"]}).encode()
        request = urllib.request.Request(args.url.rstrip("/") + "/copilot/chat", data=payload,
                                         headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                result = json.load(response)
            actual = set(result["toolsUsed"])
            expected = set(question["expectedTools"])
            ok = expected <= actual
            passed += ok
            print(json.dumps({"id": question["id"], "passed": ok,
                              "expectedTools": sorted(expected), "actualTools": sorted(actual),
                              "answer": result["answer"]}, ensure_ascii=False))
        except (urllib.error.URLError, TimeoutError, ValueError, KeyError) as error:
            print(json.dumps({"id": question["id"], "passed": False, "error": str(error)}))
    print(f"Expected-tool coverage: {passed}/{len(questions)}. Review answers manually for factual accuracy.")
    return 0 if passed == len(questions) else 1


if __name__ == "__main__":
    raise SystemExit(main())
