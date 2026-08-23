"""MAI production transcription verification layer.

This module upgrades the resumable job engine without changing its persistence protocol.
Each final-audio chunk receives an independent verifier transcription. Chunks with
meaningful disagreement receive a targeted third pass and are reconciled conservatively.
The saved AAC remains the source of truth at all times.
"""

from __future__ import annotations

import os
import re
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any, Callable

import httpx

import app as core

VERIFY_MODEL = os.getenv("MAI_VERIFY_STT_MODEL", "gpt-4o-transcribe").strip() or "gpt-4o-transcribe"
ACCURACY_MODE = os.getenv("MAI_ACCURACY_MODE", "verified").strip().lower() or "verified"
AGREEMENT_THRESHOLD = max(0.75, min(float(os.getenv("MAI_STT_AGREEMENT_THRESHOLD", "0.92")), 0.99))

_LANGUAGE_RANGES: list[tuple[str, int, int]] = [
    ("ta", 0x0B80, 0x0BFF),
    ("hi", 0x0900, 0x097F),
    ("te", 0x0C00, 0x0C7F),
    ("kn", 0x0C80, 0x0CFF),
    ("ml", 0x0D00, 0x0D7F),
    ("bn", 0x0980, 0x09FF),
    ("pa", 0x0A00, 0x0A7F),
    ("gu", 0x0A80, 0x0AFF),
    ("ur", 0x0600, 0x06FF),
]

_ROMAN_HINTS: dict[str, tuple[str, ...]] = {
    "ta": ("pannunga", "pannu", "venum", "irukku", "illa", "seri", "naala", "mudiyum"),
    "hi": ("karna", "karo", "chahiye", "nahi", "hai", "kal", "dena", "wala"),
    "te": ("cheyyali", "cheyyandi", "kavali", "undi", "ledu", "garu", "avunu"),
    "ml": ("venam", "aanu", "cheyyanam", "undu", "alle", "illa"),
    "kn": ("madbeku", "maadi", "beku", "ide", "illa", "sari"),
}

# Values that can materially change an MoM if transcribed incorrectly.
_CRITICAL_RE = re.compile(
    r"(?:₹|rs\.?|inr|\$|€|£)\s*\d[\d,]*(?:\.\d+)?|"
    r"\b\d+(?:\.\d+)?\s*%|"
    r"\b\d{1,2}[:.]\d{2}\s*(?:am|pm)?\b|"
    r"\b\d{1,2}[/-]\d{1,2}(?:[/-]\d{2,4})?\b|"
    r"\b\d[\d,]*(?:\.\d+)?\b",
    re.IGNORECASE,
)


def normalize_for_compare(value: str) -> str:
    value = value.casefold()
    value = re.sub(r"\s+", " ", value)
    value = re.sub(r"[^\w%₹$€£./:-]+", " ", value, flags=re.UNICODE)
    return re.sub(r"\s+", " ", value).strip()


def agreement(a: str, b: str) -> float:
    left = normalize_for_compare(a)
    right = normalize_for_compare(b)
    if not left and not right:
        return 1.0
    if not left or not right:
        return 0.0
    return SequenceMatcher(None, left, right).ratio()


def critical_tokens(text: str, participant_names: list[str] | None = None) -> set[str]:
    result = {normalize_for_compare(match.group(0)) for match in _CRITICAL_RE.finditer(text)}
    for name in participant_names or []:
        if name.strip() and name.casefold() in text.casefold():
            result.add("name:" + normalize_for_compare(name))
    for keyword in core.DOMAIN_KEYWORDS:
        if keyword.casefold() in text.casefold():
            result.add("term:" + normalize_for_compare(keyword))
    return {item for item in result if item}


def critical_agreement(a: str, b: str, participant_names: list[str] | None = None) -> bool:
    return critical_tokens(a, participant_names) == critical_tokens(b, participant_names)


def inferred_languages(text: str) -> list[str]:
    counts: dict[str, int] = {}
    for char in text:
        point = ord(char)
        for language, start, end in _LANGUAGE_RANGES:
            if start <= point <= end:
                counts[language] = counts.get(language, 0) + 1
                break

    lower = " " + normalize_for_compare(text) + " "
    for language, words in _ROMAN_HINTS.items():
        hits = sum(1 for word in words if re.search(rf"\b{re.escape(word)}\b", lower))
        if hits >= 2:
            counts[language] = counts.get(language, 0) + hits * 4

    ordered = [language for language, count in sorted(counts.items(), key=lambda item: item[1], reverse=True) if count > 0]
    if ordered:
        return ([ordered[0], "en"] if ordered[0] != "en" else ["en"])[:2]
    return []


def consensus_critical(pass_texts: list[str], participant_names: list[str]) -> tuple[list[str], list[str]]:
    votes: dict[str, int] = {}
    for text in pass_texts:
        for token in critical_tokens(text, participant_names):
            votes[token] = votes.get(token, 0) + 1
    consensus = sorted(token for token, count in votes.items() if count >= 2)
    unresolved = sorted(token for token, count in votes.items() if count == 1)
    return consensus, unresolved


def _transcribe_verifier(path: Path, participant_names: list[str], language_hints: list[str]) -> str:
    multipart: list[tuple[str, tuple[Any, ...]]] = [("model", (None, VERIFY_MODEL))]
    # gpt-4o-transcribe is intentionally used as an independent acoustic verifier.
    # Do not pass gpt-transcribe-only keyword/language extensions here.
    with path.open("rb") as handle:
        multipart.append(("file", (path.name, handle, "audio/mpeg")))
        with httpx.Client(timeout=httpx.Timeout(15 * 60, connect=30.0)) as client:
            response = client.post(
                f"{core.OPENAI_BASE}/audio/transcriptions",
                headers=core.openai_headers(),
                files=multipart,
            )
    if response.status_code >= 400:
        raise RuntimeError(f"Verifier STT failed ({response.status_code}): {response.text[:400]}")
    return str(response.json().get("text", "")).strip()


def _transcribe_targeted(path: Path, participant_names: list[str], languages: list[str], jobs_module: Any) -> str:
    multipart: list[tuple[str, tuple[Any, ...]]] = [("model", (None, core.STT_MODEL))]
    for language in languages[:3]:
        multipart.append(("languages[]", (None, language)))
    for keyword in core.transcription_keywords(participant_names):
        multipart.append(("keywords[]", (None, keyword)))
    with path.open("rb") as handle:
        multipart.append(("file", (path.name, handle, "audio/mpeg")))
        response = jobs_module._request_with_retry(
            "POST",
            f"{core.OPENAI_BASE}/audio/transcriptions",
            headers=core.openai_headers(),
            files=multipart,
            attempts=5,
            timeout_seconds=15 * 60,
        )
    if response.status_code >= 400:
        raise RuntimeError(f"Targeted STT failed ({response.status_code}): {response.text[:400]}")
    return str(response.json().get("text", "")).strip()


def _reconcile(
    primary: str,
    verifier: str,
    targeted: str,
    participant_names: list[str],
    jobs_module: Any,
) -> str:
    passes = [text for text in [primary, verifier, targeted] if text.strip()]
    consensus, unresolved = consensus_critical(passes, participant_names)
    names = ", ".join(participant_names) if participant_names else "none supplied"
    prompt = f"""Reconcile multiple independent speech-to-text passes of the SAME audio segment.
Return a faithful SOURCE-LANGUAGE transcript. Do not translate or summarize.

Rules:
- Preserve the spoken language and code-switching exactly as supported by the passes.
- Prefer wording supported by at least two passes.
- Known participant names: {names}.
- Consensus critical tokens below are strongly supported and must be preserved exactly when they belong in context.
- Never invent a number, amount, date, time, percentage, name, brand, task, owner or commitment.
- If the passes genuinely disagree on a critical value and there is no majority, write [unclear] at that exact point instead of selecting one.
- Fix only transcription disagreements; do not rewrite style or grammar.
- Output transcript text only.

CONSENSUS CRITICAL TOKENS:
{json_lines(consensus)}

UNRESOLVED ONE-PASS TOKENS (not trusted by themselves):
{json_lines(unresolved)}

PASS A — primary high-accuracy STT:
{primary}

PASS B — independent verifier STT:
{verifier}

PASS C — targeted language/keyword STT:
{targeted or '[not required]'}
"""
    return jobs_module._response_text_retry(core.TRANSLATE_MODEL, prompt, max_output_tokens=14000).strip()


def json_lines(values: list[str]) -> str:
    return "\n".join(f"- {value}" for value in values) if values else "- none"


def install(jobs_module: Any) -> None:
    """Install verified final-STT behavior into the persistent V1.4 job engine."""
    if getattr(jobs_module, "_mai_accuracy_installed", False):
        return

    original_transcribe: Callable[..., tuple[int, str]] = jobs_module._transcribe_one

    def verified_transcribe(path: Path, index: int, participant_names: list[str], language_mode: str) -> tuple[int, str]:
        _, primary = original_transcribe(path, index, participant_names, language_mode)
        if ACCURACY_MODE in {"off", "single", "fast"} or not primary.strip():
            return index, primary

        try:
            verifier = _transcribe_verifier(path, participant_names, [])
        except Exception:
            # Verifier failure must not destroy a good authoritative primary pass.
            return index, primary

        similarity = agreement(primary, verifier)
        critical_same = critical_agreement(primary, verifier, participant_names)
        if similarity >= AGREEMENT_THRESHOLD and critical_same:
            return index, primary

        configured_languages = list(jobs_module._languages(language_mode))
        language_hints = configured_languages or inferred_languages(primary + "\n" + verifier)
        targeted = ""
        try:
            targeted = _transcribe_targeted(path, participant_names, language_hints, jobs_module)
        except Exception:
            pass

        try:
            canonical = _reconcile(primary, verifier, targeted, participant_names, jobs_module)
            return index, canonical or primary
        except Exception:
            # Conservative fallback: if critical values disagree and reconciliation is down,
            # mark uncertainty instead of silently choosing an amount/date/number.
            if not critical_same:
                return index, primary + "\n[unclear: independent transcription passes disagreed on a critical value in this segment]"
            return index, primary

    jobs_module._transcribe_one = verified_transcribe
    jobs_module._mai_accuracy_installed = True
