"""Загрузка eval-датасета в формате docs/team/ARCHITECTURE_TEAM.md §2."""
from __future__ import annotations
import csv, json
from dataclasses import dataclass, field
from pathlib import Path
import numpy as np
import soundfile as sf


@dataclass
class Item:
    id: str
    audio_path: Path
    duration_ms: int
    condition: str
    split: str
    ref: list[dict] = field(default_factory=list)   # utterances
    meta: dict = field(default_factory=dict)        # строка manifest.csv целиком (source, scenario, ...)
    span: tuple[int, int] | None = None             # (start_ms, end_ms) — вырезка из файла (режим реплик)

    @property
    def ref_text(self) -> str:
        return " ".join(u["text"] for u in self.ref)

    def load_audio(self) -> np.ndarray:
        x, sr = sf.read(self.audio_path, dtype="float32", always_2d=True)
        assert sr == 16000, f"{self.audio_path}: sr={sr}"
        x = x.mean(axis=1)
        if self.span is not None:
            x = x[int(self.span[0] * 16): int(self.span[1] * 16)]
        return x


def load_dataset(root: str | Path, split: str | None = None, condition: str | None = None,
                 limit: int | None = None) -> list[Item]:
    root = Path(root)
    items = []
    with open(root / "manifest.csv", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if split and r.get("split") != split:
                continue
            if condition and r.get("condition") != condition:
                continue
            ref_path = root / r["ref"] if r.get("ref") else root / "ref" / f"{r['id']}.json"
            ref = json.load(open(ref_path, encoding="utf-8"))["transcript"] if ref_path.exists() else []
            items.append(Item(r["id"], root / r["audio"], int(r["duration_ms"]), r.get("condition", ""),
                              r.get("split", ""), ref, dict(r)))
    return items[:limit] if limit else items


def utterance_items(items: list[Item], pad_ms: int = 100, min_ms: int = 300) -> list[Item]:
    """Диалоги -> отдельные реплики по эталонным меткам (вырезка [start-pad, end+pad]); реплика = Item с одним ref."""
    out = []
    for it in items:
        for k, u in enumerate(it.ref):
            a, b = max(0, u["start_ms"] - pad_ms), min(it.duration_ms, u["end_ms"] + pad_ms)
            if b - a < min_ms:
                continue
            m = dict(it.meta); m["parent"] = it.id; m["speaker"] = u["speaker_id"]
            out.append(Item(f"{it.id}#{k}", it.audio_path, b - a, it.condition, it.split, [u], m, (a, b)))
    return out
