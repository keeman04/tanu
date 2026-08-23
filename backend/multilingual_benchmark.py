#!/usr/bin/env python3
"""Score MAI production results against human-verified multilingual fixtures.

Manifest format:
{
  "cases": [
    {
      "id": "ta-en-001",
      "language": "Tamil + English",
      "reference_transcript": "...",
      "actual_transcript": "...",
      "critical_entities": ["Karthick", "₹15,000", "27 August 2026"],
      "expected_decisions": ["Approve the campaign budget"],
      "expected_actions": ["Complete Rednote integration"],
      "actual_summary": "...",
      "actual_decisions": ["..."],
      "actual_actions": ["..."]
    }
  ]
}

The reference transcript must be created/reviewed by a human from the original audio.
"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

from accuracy_metrics import score


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--max-wer", type=float, default=0.10)
    parser.add_argument("--min-critical", type=float, default=0.98)
    parser.add_argument("--min-decision", type=float, default=0.95)
    parser.add_argument("--min-action", type=float, default=0.95)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    payload = json.loads(args.manifest.read_text(encoding="utf-8"))
    cases = payload.get("cases", [])
    if not isinstance(cases, list) or not cases:
        raise SystemExit("Benchmark manifest must contain at least one case")

    by_language: dict[str, list[dict]] = defaultdict(list)
    failures: list[str] = []
    output: list[dict] = []

    for case in cases:
        result = score(
            reference_transcript=str(case.get("reference_transcript", "")),
            actual_transcript=str(case.get("actual_transcript", "")),
            expected_critical_entities=case.get("critical_entities", []) or [],
            expected_decisions=case.get("expected_decisions", []) or [],
            expected_actions=case.get("expected_actions", []) or [],
            actual_summary=str(case.get("actual_summary", "")),
            actual_decisions=case.get("actual_decisions", []) or [],
            actual_actions=case.get("actual_actions", []) or [],
            max_wer=args.max_wer,
            min_critical_accuracy=args.min_critical,
            min_decision_recall=args.min_decision,
            min_action_recall=args.min_action,
        )
        row = {
            "id": str(case.get("id", "unnamed")),
            "language": str(case.get("language", "unknown")),
            **result.as_dict(),
        }
        output.append(row)
        by_language[row["language"]].append(row)
        if not result.passed:
            failures.append(row["id"])

    summary: dict[str, dict] = {}
    for language, rows in sorted(by_language.items()):
        summary[language] = {
            "cases": len(rows),
            "avg_transcript_accuracy": sum(row["transcript_accuracy"] for row in rows) / len(rows),
            "avg_critical_entity_accuracy": sum(row["critical_entity_accuracy"] for row in rows) / len(rows),
            "avg_decision_recall": sum(row["decision_recall"] for row in rows) / len(rows),
            "avg_action_recall": sum(row["action_recall"] for row in rows) / len(rows),
            "all_cases_passed": all(row["passed"] for row in rows),
        }

    report = {"cases": output, "languages": summary, "passed": not failures, "failed_cases": failures}
    print(json.dumps(report, indent=2, ensure_ascii=False))
    if failures:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
