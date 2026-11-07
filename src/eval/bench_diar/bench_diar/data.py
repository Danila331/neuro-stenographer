"""Dataset access (ARCHITECTURE sec. 2 format) + RTTM helpers."""
from __future__ import annotations

import csv
import os
import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import soundfile as sf

from .metrics import Segments

ROOT = Path(os.environ.get("NS_ROOT") or Path(__file__).resolve().parents[6])
CACHE = ROOT / ".cache"


@dataclass
class Item:
    id: str
    audio: Path
    ref: Segments
    duration: float
    num_speakers: int
    condition: str
    split: str
    source: str


def read_rttm(p: Path) -> Segments:
    out: Segments = []
    for line in p.read_text().splitlines():
        f = line.split()
        if len(f) >= 8 and f[0] == "SPEAKER":
            out.append((f[7], float(f[3]), float(f[3]) + float(f[4])))
    return out


def write_rttm(p: Path, uid: str, segs: Segments) -> None:
    with open(p, "w") as f:
        for s, a, b in sorted(segs, key=lambda x: (x[1], x[2])):
            f.write(f"SPEAKER {uid} 1 {a:.3f} {b - a:.3f} <NA> <NA> {s} <NA> <NA>\n")


def ref_from_json(p: Path) -> Segments:
    d = json.loads(p.read_text())
    return [(u["speaker_id"], u["start_ms"] / 1000, u["end_ms"] / 1000) for u in d["transcript"]]


def load_dataset(root: Path | str, split: str | None = None, condition: str | None = None,
                 limit: int | None = None) -> list[Item]:
    root = Path(root)
    items: list[Item] = []
    with open(root / "manifest.csv", newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if split and r.get("split") != split:
                continue
            if condition and r.get("condition") != condition:
                continue
            rid = r["id"]
            rttm = root / "rttm" / f"{rid}.rttm"
            ref = read_rttm(rttm) if rttm.exists() else ref_from_json(root / r["ref"])
            items.append(Item(
                id=rid, audio=root / r["audio"], ref=ref,
                duration=float(r["duration_ms"]) / 1000, num_speakers=int(r["num_speakers"]),
                condition=r.get("condition", ""), split=r.get("split", ""), source=r.get("source", ""),
            ))
    return items[:limit] if limit else items


def load_audio(p: Path) -> np.ndarray:
    x, sr = sf.read(str(p), dtype="float32", always_2d=False)
    if x.ndim > 1:
        x = x.mean(1)
    assert sr == 16000, f"{p}: expected 16 kHz, got {sr}"
    return np.ascontiguousarray(x)
