"""Общие утилиты: пути, детерминированный RNG, запись датасета в формате ARCHITECTURE §2."""
from __future__ import annotations

import csv
import json
import os
import shutil
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 16_000
OUR_DATASETS = ["ru-asr-dev", "ru-dialog-synth", "ru-dialog-tts-cats", "real"]
MANIFEST_COLS = ["id", "audio", "ref", "duration_ms", "num_speakers", "lang", "source", "condition", "split"]


def find_root() -> Path:
    """$ROOT репозитория: STENO_ROOT или предок каталога `.worktrees`."""
    if os.environ.get("STENO_ROOT"):
        return Path(os.environ["STENO_ROOT"]).resolve()
    p = Path(__file__).resolve()
    for a in p.parents:
        if a.name == ".worktrees":
            return a.parent
    for a in p.parents:
        if (a / ".git").exists():
            return a
    raise RuntimeError("cannot locate repo root; set STENO_ROOT")


def data_root() -> Path:
    return Path(os.environ.get("STENO_DATA", find_root() / ".cache" / "data"))


def raw_root() -> Path:
    r = Path(os.environ["STENO_RAW"]) if os.environ.get("STENO_RAW") else data_root() / ".raw"
    r.mkdir(parents=True, exist_ok=True)
    return r


def models_root() -> Path:
    return Path(os.environ.get("STENO_MODELS", find_root() / ".cache" / "models"))


def rng_for(seed: int, *keys) -> np.random.Generator:
    """Детерминированный RNG, зависящий от seed и ключей (стабилен между запусками)."""
    import hashlib
    h = hashlib.sha256(("|".join(map(str, (seed, *keys)))).encode()).digest()
    return np.random.default_rng(int.from_bytes(h[:8], "little"))


def stable_split(key: str, test_frac: float = 0.3, salt: str = "steno-v1") -> str:
    """dev/test по хэшу ключа (стабильно при расширении набора)."""
    import hashlib
    v = int.from_bytes(hashlib.sha256(f"{salt}|{key}".encode()).digest()[:4], "little") / 2**32
    return "test" if v < test_frac else "dev"


def to_ms(samples: int) -> int:
    return int(round(samples * 1000 / SR))


def read_audio_16k(path: str | Path) -> np.ndarray:
    """Читает любой поддерживаемый soundfile файл -> mono float32 16 кГц."""
    x, sr = sf.read(str(path), dtype="float32", always_2d=True)
    x = x.mean(axis=1)
    if sr != SR:
        import soxr
        x = soxr.resample(x, sr, SR, quality="HQ")
    return x.astype(np.float32)


def write_wav16(path: str | Path, x: np.ndarray):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    x = np.clip(x, -1.0, 1.0)
    sf.write(str(path), x, SR, subtype="PCM_16")


def renumber_speakers(utts: list[dict]) -> list[dict]:
    """Приводит speaker_id к speaker_1..N по порядку первого появления (по start_ms)."""
    utts = sorted(utts, key=lambda u: (u["start_ms"], u["end_ms"]))
    m: dict = {}
    out = []
    for u in utts:
        sid = u["speaker_id"]
        if sid not in m:
            m[sid] = f"speaker_{len(m) + 1}"
        out.append({"speaker_id": m[sid], "start_ms": int(u["start_ms"]), "end_ms": int(u["end_ms"]), "text": u["text"]})
    return out


def write_ref(path: str | Path, utts: list[dict]):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"transcript": renumber_speakers(utts)}, f, ensure_ascii=False, separators=(",", ":"))


def dataset_dir(name: str) -> Path:
    return data_root() / name


class DatasetWriter:
    """Пишет датасет во временную папку; `finalize()` атомарно подменяет итоговую."""

    def __init__(self, name: str, extra_cols: list[str] | None = None):
        self.name = name
        self.final = dataset_dir(name)
        self.tmp = data_root() / f".tmp-datasets-{name}"
        if self.tmp.exists():
            shutil.rmtree(self.tmp)
        (self.tmp / "audio").mkdir(parents=True)
        (self.tmp / "ref").mkdir()
        self.cols = MANIFEST_COLS + (extra_cols or [])
        self.rows: list[dict] = []

    def add(self, id: str, audio: np.ndarray, utts: list[dict], *, num_speakers: int, source: str,
            condition: str, split: str, lang: str = "ru", **extra):
        write_wav16(self.tmp / "audio" / f"{id}.wav", audio)
        write_ref(self.tmp / "ref" / f"{id}.json", utts)
        row = dict(id=id, audio=f"audio/{id}.wav", ref=f"ref/{id}.json", duration_ms=to_ms(len(audio)),
                   num_speakers=num_speakers, lang=lang, source=source, condition=condition, split=split)
        row.update(extra)
        self.rows.append(row)

    def finalize(self, readme: str | None = None):
        with open(self.tmp / "manifest.csv", "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=self.cols, extrasaction="ignore")
            w.writeheader()
            for r in sorted(self.rows, key=lambda r: r["id"]):
                w.writerow(r)
        if readme:
            (self.tmp / "README.txt").write_text(readme, encoding="utf-8")
        if self.final.exists():
            shutil.rmtree(self.final)
        os.rename(self.tmp, self.final)
        return self.final
