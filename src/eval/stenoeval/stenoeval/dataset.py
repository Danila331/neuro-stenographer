"""Eval dataset (ARCHITECTURE.md section 2) and run directory readers."""

from __future__ import annotations

import csv
import json
import wave
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Item:
    id: str
    ref_path: Path
    audio_path: Path | None
    duration_ms: int | None
    num_speakers: int | None
    condition: str
    split: str
    lang: str
    source: str


def _int(v: str | None) -> int | None:
    try:
        return int(float(v)) if v not in (None, "") else None
    except ValueError:
        return None


def read_manifest(root: Path) -> list[Item]:
    mf = root / "manifest.csv"
    if not mf.is_file():
        raise FileNotFoundError(f"manifest.csv not found in {root}")
    items: list[Item] = []
    with mf.open(newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            fid = (row.get("id") or "").strip()
            if not fid:
                continue
            ref_col = (row.get("ref") or "").strip()
            ref_path = root / ref_col if ref_col and (root / ref_col).is_file() else root / "ref" / f"{fid}.json"
            a_col = (row.get("audio") or "").strip()
            audio = root / a_col if a_col else root / "audio" / f"{fid}.wav"
            items.append(
                Item(
                    id=fid,
                    ref_path=ref_path,
                    audio_path=audio if audio.is_file() else None,
                    duration_ms=_int(row.get("duration_ms")),
                    num_speakers=_int(row.get("num_speakers")),
                    condition=(row.get("condition") or "").strip(),
                    split=(row.get("split") or "").strip(),
                    lang=(row.get("lang") or "").strip(),
                    source=(row.get("source") or "").strip(),
                )
            )
    return items


def wav_duration_ms(path: Path) -> int | None:
    try:
        with wave.open(str(path), "rb") as w:
            return round(w.getnframes() * 1000 / w.getframerate())
    except Exception:
        return None


def read_meta(run_dir: Path, fid: str) -> dict:
    p = run_dir / f"{fid}.meta.json"
    if not p.is_file():
        return {}
    try:
        d = json.loads(p.read_text(encoding="utf-8"))
        return d if isinstance(d, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}
