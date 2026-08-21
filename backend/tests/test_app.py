from app import normalize_result


def test_normalize_deduplicates_decisions_and_actions_and_merges_owners():
    result = normalize_result(
        {
            "summary": "Pricing was finalized.",
            "language": "Tamil + English (Tanglish)",
            "decisions": ["Use option B", "Use option B"],
            "actions": [
                {"text": "Share revised quotation", "owner": "Ravi", "due": "Tomorrow"},
                {"text": "Share revised quotation", "owner": "Manoj", "due": None},
            ],
        },
        "m1",
        "transcript",
    )
    assert result.summary == "Pricing was finalized."
    assert result.decisions == ["Use option B"]
    assert len(result.actions) == 1
    assert result.actions[0].owner == "Ravi / Manoj"
    assert result.actions[0].due == "Tomorrow"


def test_normalize_uses_safe_summary():
    result = normalize_result({}, "m2", "hello")
    assert result.summary == "Meeting recorded."
    assert result.transcript == "hello"
