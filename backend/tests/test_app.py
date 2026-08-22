from app import canonical_owner, language_hints, normalize_result, transcription_keywords


def test_normalize_deduplicates_decisions_and_actions_and_merges_known_owners():
    result = normalize_result(
        {
            "summary": "Pricing was finalized.",
            "language": "Tamil + English (Tanglish)",
            "decisions": ["Use option B", "Use option B"],
            "actions": [
                {"text": "Share revised quotation", "owner": "Ravi", "due": "2026-08-23"},
                {"text": "Share revised quotation", "owner": "Manoj", "due": None},
            ],
        },
        "m1",
        "Verified English transcript",
        ["Ravi", "Manoj"],
    )
    assert result.summary == "Pricing was finalized."
    assert result.decisions == ["Use option B"]
    assert result.transcript == "Verified English transcript"
    assert len(result.actions) == 1
    assert result.actions[0].owner == "Ravi / Manoj"
    assert result.actions[0].due == "2026-08-23"


def test_normalize_rejects_hallucinated_owner():
    result = normalize_result(
        {
            "actions": [{"text": "Call the vendor", "owner": "Unknown Person", "due": None}],
        },
        "m2",
        "Call the vendor.",
        ["Ravi"],
    )
    assert result.actions[0].owner is None


def test_normalize_uses_safe_summary():
    result = normalize_result({}, "m3", "hello", [])
    assert result.summary == "Meeting recorded."
    assert result.transcript == "hello"


def test_tamil_english_hints_are_always_enabled():
    assert language_hints() == ["ta", "en"]


def test_participant_names_are_high_priority_transcription_keywords():
    keywords = transcription_keywords(["Karthick", "Wilson"])
    assert keywords[:2] == ["Karthick", "Wilson"]
    assert "VGP" in keywords
    assert "WhatsApp" in keywords


def test_owner_resolution_uses_exact_participant_names_only():
    assert canonical_owner("ravi / MANOJ", ["Ravi", "Manoj", "Wilson"]) == "Ravi / Manoj"
    assert canonical_owner("Marketing team", ["Ravi", "Manoj"]) is None
