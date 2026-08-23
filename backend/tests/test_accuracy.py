import accuracy
from accuracy_metrics import score, word_error_rate


def test_infers_indian_scripts_and_romanized_tanglish():
    assert accuracy.inferred_languages("நாளைக்கு budget final பண்ணலாம்") == ["ta", "en"]
    assert accuracy.inferred_languages("budget pannunga, naala complete pannu") == ["ta", "en"]
    assert accuracy.inferred_languages("రేపు budget final cheyyali") == ["te", "en"]
    assert accuracy.inferred_languages("कल budget final करना है") == ["hi", "en"]


def test_critical_tokens_catch_names_money_percentages_dates_and_numbers():
    text = "Karthick approved ₹15,000, 20% for VGP Waghoba on 27/08/2026 at 4:30 pm."
    tokens = accuracy.critical_tokens(text, ["Karthick", "Ravi"])
    assert "name:karthick" in tokens
    assert any("15 000" in token for token in tokens)
    assert any("20%" in token for token in tokens)
    assert any("27/08/2026" in token for token in tokens)
    assert "term:vgp waghoba" in tokens


def test_critical_disagreement_forces_verification():
    a = "Budget is ₹15,000 and Karthick owns it."
    b = "Budget is ₹50,000 and Karthick owns it."
    assert not accuracy.critical_agreement(a, b, ["Karthick"])
    consensus, unresolved = accuracy.consensus_critical([a, b], ["Karthick"])
    assert "name:karthick" in consensus
    assert any("15 000" in token for token in unresolved)
    assert any("50 000" in token for token in unresolved)


def test_wer_and_release_thresholds():
    assert word_error_rate("complete rednote integration friday", "complete rednote integration friday") == 0
    result = score(
        reference_transcript="Karthick complete Rednote integration by Friday with budget rs 15000",
        actual_transcript="Karthick complete Rednote integration by Friday with budget rs 15000",
        expected_critical_entities=["Karthick", "rs 15000"],
        expected_decisions=["Rednote integration"],
        expected_actions=["complete Rednote integration"],
        actual_summary="Rednote integration will be completed.",
        actual_decisions=["Rednote integration approved"],
        actual_actions=["Karthick complete Rednote integration"],
    )
    assert result.passed
    assert result.critical_entity_accuracy == 1.0
