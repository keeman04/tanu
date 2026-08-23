import os
import re
import shutil
import subprocess
from pathlib import Path

SEGMENT_SECONDS = max(120, min(int(os.getenv("MAI_SEGMENT_SECONDS", "420")), 900))
OVERLAP_SECONDS = max(0, min(int(os.getenv("MAI_SEGMENT_OVERLAP_SECONDS", "5")), 15))
SILENCE_DB = os.getenv("MAI_SILENCE_THRESHOLD_DB", "-38dB").strip() or "-38dB"
SILENCE_MIN_SECONDS = max(0.2, min(float(os.getenv("MAI_SILENCE_MIN_SECONDS", "0.35")), 2.0))


def duration_seconds(path: Path) -> float:
    if shutil.which("ffprobe") is None:
        raise RuntimeError("ffprobe is required on the MAI backend")
    completed = subprocess.run(
        [
            "ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1", str(path),
        ],
        capture_output=True,
        text=True,
        timeout=120,
    )
    if completed.returncode != 0:
        raise RuntimeError("Could not inspect meeting audio duration")
    try:
        return max(0.0, float(completed.stdout.strip()))
    except ValueError as exc:
        raise RuntimeError("Meeting audio duration was invalid") from exc


def silence_midpoints(source: Path) -> list[float]:
    """Return centers of real pauses. Failure falls back to fixed-time boundaries."""
    if shutil.which("ffmpeg") is None:
        return []
    completed = subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-nostats", "-i", str(source),
            "-af", f"silencedetect=noise={SILENCE_DB}:d={SILENCE_MIN_SECONDS}",
            "-f", "null", "-",
        ],
        capture_output=True,
        text=True,
        timeout=20 * 60,
    )
    if completed.returncode != 0:
        return []

    pending_starts: list[float] = []
    midpoints: list[float] = []
    for line in completed.stderr.splitlines():
        start_match = re.search(r"silence_start:\s*([0-9.]+)", line)
        if start_match:
            pending_starts.append(float(start_match.group(1)))
        end_match = re.search(r"silence_end:\s*([0-9.]+)", line)
        if end_match:
            end = float(end_match.group(1))
            start = pending_starts.pop(0) if pending_starts else max(0.0, end - SILENCE_MIN_SECONDS)
            midpoints.append((start + end) / 2.0)
    return midpoints


def choose_boundaries(duration: float, silence_points: list[float]) -> list[float]:
    if duration <= 0:
        return [0.0]
    boundaries = [0.0]
    current = 0.0
    search_radius = min(60.0, max(20.0, SEGMENT_SECONDS * 0.12))
    minimum_segment = min(120.0, SEGMENT_SECONDS * 0.4)

    while current + SEGMENT_SECONDS < duration:
        target = current + SEGMENT_SECONDS
        candidates = [
            point for point in silence_points
            if target - search_radius <= point <= target + search_radius
            and point - current >= minimum_segment
            and duration - point >= minimum_segment
        ]
        boundary = min(candidates, key=lambda point: abs(point - target)) if candidates else target
        if boundary <= current + 1.0:
            boundary = target
        boundaries.append(min(boundary, duration))
        current = boundaries[-1]

    if boundaries[-1] < duration:
        boundaries.append(duration)
    return boundaries


def segment_audio(source: Path, output_dir: Path) -> list[Path]:
    """Split near natural pauses, preserving overlap and a deterministic time fallback."""
    if shutil.which("ffmpeg") is None:
        raise RuntimeError("ffmpeg is required on the MAI backend")
    duration = duration_seconds(source)
    if duration <= 0:
        raise RuntimeError("Meeting audio contains no usable duration")

    output_dir.mkdir(parents=True, exist_ok=True)
    boundaries = choose_boundaries(duration, silence_midpoints(source))
    segments: list[Path] = []

    for index in range(len(boundaries) - 1):
        logical_start = boundaries[index]
        logical_end = boundaries[index + 1]
        start = max(0.0, logical_start - (OVERLAP_SECONDS if index > 0 else 0.0))
        length = logical_end - start
        target = output_dir / f"segment-{index:04d}.mp3"
        completed = subprocess.run(
            [
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-ss", f"{start:.3f}", "-i", str(source), "-t", f"{length:.3f}",
                "-vn", "-ac", "1", "-ar", "16000", "-b:a", "32k", str(target),
            ],
            capture_output=True,
            text=True,
            timeout=20 * 60,
        )
        if completed.returncode != 0 or not target.is_file() or target.stat().st_size <= 512:
            raise RuntimeError(f"Audio segment {index + 1} conversion failed: {completed.stderr[-300:]}")
        segments.append(target)

    return segments
