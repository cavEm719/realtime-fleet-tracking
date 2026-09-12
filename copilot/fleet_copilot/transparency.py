"""User-facing query basis: conditions and tables come from the API's execution, never the model.

The API renders each query's conditions and result tables. This module only adds assumptions that
depend on the operator's wording and assembles the final text.
"""
import json
import re
from dataclasses import dataclass, field

from .timerange import iso


@dataclass(frozen=True)
class Table:
    title: str
    columns: list[str]
    rows: list[list[str]]


@dataclass(frozen=True)
class Basis:
    tool: str
    arguments: dict[str, str]
    text: str
    assumptions: list[str] = field(default_factory=list)
    results: list[Table] = field(default_factory=list)
    data_as_of: str | None = None

    def to_json(self) -> dict:
        return {
            "tool": self.tool,
            "arguments": self.arguments,
            "text": self.text,
            "assumptions": self.assumptions,
            "results": [t.__dict__ for t in self.results],
            "dataAsOf": self.data_as_of,
        }


_ISO_IN_TEXT = re.compile(r"(?s).*\d{4}-\d{2}-\d{2}t[^ ]*z.*")
_RADIUS_IN_TEXT = re.compile(r"(?s).*\d+(?:\.\d+)?\s*(?:km|kilomet|m\b|meter|公里|千米|米).*")


def time_text(args: dict[str, str]) -> str:
    start = args.get("fromUtc") or (iso(int(args["fromTs"])) if "fromTs" in args else "unbounded start")
    end = args.get("toUtc") or (iso(int(args["toTs"])) if "toTs" in args else "unbounded end")
    return f"Time: {start} .. {end} (inclusive UTC)"


def assumptions(tool: str, args: dict[str, str], question: str, window: str | None) -> list[str]:
    text = (question or "").lower()
    notes = []
    if (
        ("fromTs" in args or "toTs" in args or "fromUtc" in args)
        and "utc" not in text
        and "协调世界时" not in text
        and not _ISO_IN_TEXT.match(text)
    ):
        notes.append("Time zone: UTC (system convention; no UTC zone stated in this message).")
    if args.get("eventType") == "ENTER" and "enter" not in text:
        notes.append(
            "Arrival means a confirmed port ENTER event, not a raw position or a distinct vessel."
        )
    if window is not None:
        notes.append(
            f"Time interpretation: {window} = {time_text(args)}"
            + (
                " (UTC midnight through the fixed/current request time)."
                if window == "today"
                else " (UTC system convention)."
            )
        )
    if tool == "findNearby" and not _RADIUS_IN_TEXT.match(text):
        notes.append(f"Radius: {args.get('radiusM')} m (selected without a numeric radius in this message).")
    if tool == "listEvents" and "fromTs" not in args and "toTs" not in args:
        notes.append("No time filter: newest matching events across all retained history.")
    if tool in ("listEvents", "listFences", "findNearby"):
        notes.append(f"Returned-row limit: {args.get('limit', '50')}; a bounded list is not a full count.")
    return notes


def fact_table(values: dict) -> Table:
    return Table("Key results", ["Fact", "Value"], [[k, _cell(v)] for k, v in values.items()])


def _cell(value) -> str:
    if value is None:
        return "not available"
    text = json.dumps(value, separators=(",", ":")) if isinstance(value, (dict, list)) else str(value)
    return text if len(text) <= 512 else text[:512] + " [truncated]"


def _safe(value: str) -> str:
    for old, new in (
        ("&", "&amp;"), ("<", "&lt;"), (">", "&gt;"), ("|", "\\|"), ("\n", " "), ("\r", " "),
        ("[", "\\["), ("]", "\\]"), ("`", "\\`"), ("*", "\\*"), ("_", "\\_"),
    ):
        value = value.replace(old, new)
    return value


def prepend(entries: list[Basis], summary: str) -> str:
    out = ["Query basis\n"]
    if not entries:
        out.append("No successful data query was executed this turn.\n")
    for entry in entries:
        out.append("\n" + _safe(entry.text) + "\n")
        if entry.data_as_of:
            out.append(f"Port events and states as of: {_safe(entry.data_as_of)} (replicated data)\n")
        for note in entry.assumptions:
            out.append("Assumption: " + _safe(note) + "\n")
        for table in entry.results:
            out.append("\n" + _safe(table.title) + "\n")
            if not table.rows:
                out.append("No matching rows.\n")
                continue
            out.append("| " + " | ".join(_safe(c) for c in table.columns) + " |\n")
            out.append("| " + " | ".join("---" for _ in table.columns) + " |\n")
            for row in table.rows:
                out.append("| " + " | ".join(_safe(c) for c in row) + " |\n")
    out.append("\nSummary\n" + summary)
    return "".join(out)
