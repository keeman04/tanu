"""Deterministic accuracy metrics for MAI multilingual certification."""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass
from typing import Iterable


@dataclass(frozen=True)
class AccuracyScore:
    wer: float
    transcript_accuracy: float
    critical_entity_accuracy: float
    decision_recall: float
    action_recall: float
    passed: bool

    def as_dict(self) -> dict:
        return asdict(self)


def _words(text: str) -> list[str]:
    normalized = text.casefold().replace("₹", " rs ")
    normalized = re.sub(r"[^\w%./:-]+", " ", normalized, flags=re.UNICODE)
    return [token for token in normalized.split() if token]


def word_error_rate(reference: str, hypothesis: str) -> float:
    ref = _words(reference)
    hyp = _words(hypothesis)
    if not ref:
        return 0.0 if not hyp else 1.0
    previous = list(range(len(hyp) + 1))
    for i, ref_word in enumerate(ref, start=1):
        current = [i]
        for j, hyp_word in enumerate(hyp, start=1):
            substitution = previous[j - 1] + (0 if ref_word == hyp_word else 1)
            insertion = current[j - 1] + 1
            deletion = previous[j] + 1
            current.append(min(substitution, insertion, deletion))
        previous = current
    return min(1.0, previous[-1] / max(1, len(ref)))


def _phrase_present(phrase: str, text: str) -> bool:
    phrase_words = _words(phrase)
    text_words = _words(text)
    if not phrase_words:
        return True
    if len(phrase_words) == 1:
        return phrase_words[0] in text_words
    needle = " ".join(phrase_words)
    haystack = " ".join(text_words)
    return needle in haystack


def recall(expected: Iterable[str], actual_text: str) -> float:
    values = [value for value in expected if value.strip()]
    if not values:
        return 1.0
    hits = sum(1 for value in values if _phrase_present(value, actual_text))
    return hits / len(values)


def score(
    *,
    reference_transcript: str,
    actual_transcript: str,
    expected_critical_entities: Iterable[str] = (),
    expected_decisions: Iterable[str] = (),
    expected_actions: Iterable[str] = (),
    actual_summary: str = "",
    actual_decisions: Iterable[str] = (),
    actual_actions: Iterable[str] = (),
    max_wer: float = 0.10,
    min_critical_accuracy: float = 0.98,
    min_decision_recall: float = 0.95,
    min_action_recall: float = 0.95,
) -> AccuracyScore:
    wer = word_error_rate(reference_transcript, actual_transcript)
    transcript_accuracy = max(0.0, 1.0 - wer)
    critical_accuracy = recall(expected_critical_entities, actual_transcript)
    decision_text = "\n".join([actual_summary, *actual_decisions])
    action_text = "\n".join(actual_actions)
    decision_recall = recall(expected_decisions, decision_text)
    action_recall = recall(expected_actions, action_text)
    passed = (
        wer <= max_wer
        and critical_accuracy >= min_critical_accuracy
        and decision_recall >= min_decision_recall
        and action_recall >= min_action_recall
    )
    return AccuracyScore(
        wer=wer,
        transcript_accuracy=transcript_accuracy,
        critical_entity_accuracy=critical_accuracy,
        decision_recall=decision_recall,
        action_recall=action_recall,
        passed=passed,
    )
