#!/usr/bin/env python3
"""Validate Phase 8 structure, local links, source lines and frozen evidence offline."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SECTIONS = [
    "What: the problem in plain words, with a concrete example",
    "Why: requirements and constraints that drove the design",
    "How: the design, with pointers to key code (file:line), SQL, Lua, proto",
    "Alternatives considered and trade-offs",
    "Failure modes and how each is handled",
    "Problems encountered and how they were solved",
    "Evidence: tests by name, measured numbers with conditions",
    "Known limits and what production would need",
    "Likely interview questions with short, precise answers",
]
failures = []
files = [ROOT / "README.md", ROOT / "docs/architecture.md", ROOT / "docs/runbook.md", ROOT / "docs/journal.md"]
files += sorted((ROOT / "docs/design").glob("*.md"))
files += sorted((ROOT / "docs/decisions").glob("*.md"))
files += [ROOT / "docs/verification/legacy-project-reference.md"]
if len(list((ROOT / "docs/design").glob("*.md"))) != 12:
    failures.append("Expected twelve design topics")
for file in files:
    text = file.read_text()
    if file.parent.name == "design":
        headings = re.findall(r"^## (.*)$", text, re.MULTILINE)
        if headings != SECTIONS:
            failures.append(f"Unexpected section structure: {file.relative_to(ROOT)}")
        questions = len(re.findall(r"^\*\*Q:", text, re.MULTILINE))
        if not 8 <= questions <= 12:
            failures.append(f"Expected 8–12 questions: {file.relative_to(ROOT)}")
    for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", text):
        if target.startswith(("https://", "http://", "mailto:", "#")):
            continue
        target = target.strip("<>")
        path, _, anchor = target.partition("#")
        resolved = (file.parent / path).resolve()
        if not resolved.exists():
            failures.append(f"Missing link in {file.relative_to(ROOT)}: {target}")
        elif re.fullmatch(r"L\d+", anchor):
            if int(anchor[1:]) > len(resolved.read_text().splitlines()):
                failures.append(f"Source line exceeds file: {target}")
    for test_class, method in re.findall(r"\b(\w+(?:Test|IT))\.(\w+)\b", text):
        candidates = list(ROOT.glob(f"*/src/test/**/{test_class}.java"))
        if not candidates or not any(re.search(r"\b" + re.escape(method) + r"\s*\(", p.read_text()) for p in candidates):
            failures.append(f"Unknown test in {file.relative_to(ROOT)}: {test_class}.{method}")
freeze = json.loads((ROOT / "copilot-eval/checker-freeze.json").read_text())
expected = {freeze["checkerFile"]: freeze["checkerSha256"], **freeze["reviewFiles"]}
for file, digest in expected.items():
    actual = hashlib.sha256((ROOT / file).read_bytes()).hexdigest()
    if actual != digest:
        failures.append(f"Frozen evidence changed: {file}")
# All reviewed tasks, references and retained results must also remain unchanged.
protected = ["copilot-eval", "docs/review", "bench/results"]
changed = subprocess.check_output(["git", "diff", "HEAD", "--name-only", "--", *protected], cwd=ROOT, text=True)
if changed.strip():
    failures.append("Protected evaluation or measurement files changed")
tracked_private = subprocess.check_output(["git", "ls-files", "--", "interview/"], cwd=ROOT, text=True)
if tracked_private.strip():
    failures.append("Private interview files are tracked")
if failures:
    print("\n".join(failures))
    sys.exit(1)
print(f"PASS: {len(files)} documents; 12 exact topic structures; links, source bounds, named tests, frozen hashes and private-file exclusion")
