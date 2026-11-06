"""Loading of transcripts in the ТЗ format (ARCHITECTURE.md section 1)."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class Utt:
    speaker_id: str
    start_ms: int
    end_ms: int
    text: str


class StrictJSONError(ValueError):
    """Raised for JSON that is syntactically valid for Python but not strict."""


def _no_dup_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    d: dict[str, Any] = {}
    for k, v in pairs:
        if k in d:
            raise StrictJSONError(f"duplicate JSON key {k!r}")
        d[k] = v
    return d


def _bad_constant(name: str) -> Any:
    raise StrictJSONError(f"non-standard JSON constant {name} (NaN/Infinity are not allowed)")


def strict_loads(text: str) -> Any:
    """json.loads that rejects duplicate keys and NaN/Infinity."""
    return json.loads(text, object_pairs_hook=_no_dup_pairs, parse_constant=_bad_constant)


def read_text_strict(path: Path) -> str:
    raw = path.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf"):
        raise StrictJSONError("file starts with a UTF-8 BOM")
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError as e:
        raise StrictJSONError(f"file is not valid UTF-8: {e}") from e


def utts_from_obj(obj: Any) -> list[Utt]:
    """Lenient conversion (used for scoring): tolerate type quirks, skip unusable items.

    Strictness is the job of :mod:`stenoeval.validate`; scoring should still produce a number
    for a slightly malformed hypothesis (and report the problem separately).
    """
    items = obj.get("transcript") if isinstance(obj, dict) else None
    out: list[Utt] = []
    if not isinstance(items, list):
        return out
    for it in items:
        if not isinstance(it, dict):
            continue
        text = it.get("text")
        if not isinstance(text, str):
            continue
        try:
            s = int(float(it.get("start_ms", 0)))
            e = int(float(it.get("end_ms", s)))
        except (TypeError, ValueError, OverflowError):
            continue
        out.append(Utt(str(it.get("speaker_id", "speaker_1")), s, e, text))
    return out


def sort_utts(utts: list[Utt]) -> list[Utt]:
    """Order by (start_ms, end_ms); stable, so file order breaks remaining ties."""
    return sorted(utts, key=lambda u: (u.start_ms, u.end_ms))


def load_transcript(path: Path) -> list[Utt]:
    """Load a transcript leniently (raises on unreadable / non-JSON files)."""
    return utts_from_obj(strict_loads(read_text_strict(path)))
