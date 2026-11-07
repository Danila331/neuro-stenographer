"""Dataset format §2: <root>/manifest.csv (id,audio,ref,duration_ms,num_speakers,lang,source,condition,split)."""
from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Item:
    id: str
    audio: Path
    num_speakers: int | None
    split: str
    condition: str


def load_manifest(root: Path, split: str | None = None, ids: list[str] | None = None) -> list[Item]:
    items = []
    with (root / "manifest.csv").open(encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            if split and r.get("split") and r["split"] != split:
                continue
            if ids and r["id"] not in ids:
                continue
            a = Path(r["audio"])
            a = a if a.is_absolute() else root / a
            ns = r.get("num_speakers") or ""
            items.append(Item(r["id"], a, int(ns) if ns.strip().isdigit() else None,
                              r.get("split", ""), r.get("condition", "")))
    return items
