"""Corpora (reference / pipeline transcripts), gold mapping, and the question set loader."""
from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent  # .../src/eval/bench_rag


def find_root() -> Path:
    if os.environ.get("ROOT"):
        return Path(os.environ["ROOT"])
    p = HERE
    while p != p.parent:
        if (p / ".git").exists() and p.parent.name == ".worktrees":
            return p.parent.parent
        if (p / ".cache").exists():
            return p
        p = p.parent
    raise RuntimeError("set ROOT")


ROOT = find_root()
CACHE = ROOT / ".cache"
MODELS = CACHE / "models"
REF_DIRS = [CACHE / "data" / "ru-dialog-tts-cats" / "ref", CACHE / "data" / "ru-dialog-synth" / "ref"]
ASR_DIR = CACHE / "runs" / "refpipe-medium-relabel"
DIALOGS = HERE.parent / "bench_llm" / "data" / "dialogs"


@dataclass
class Utt:
    idx: int
    speaker: str          # "speaker_N"
    start_ms: int
    end_ms: int
    text: str

    @property
    def spk(self) -> int:
        return int(self.speaker.split("_")[1])


@dataclass
class Record:
    id: str
    utts: list[Utt]
    names: dict[str, str] = field(default_factory=dict)   # speaker_N -> user-given display name
    title: str = ""

    @property
    def duration_ms(self) -> int:
        return max((u.end_ms for u in self.utts), default=0)


def load_record(rid: str, kind: str = "ref") -> Record:
    """rid: 'c03' -> cats_c03_v0_*; or a full file stem. kind: ref | asr."""
    if kind == "ref":
        files = [f for d in REF_DIRS for f in d.glob("*.json")]
        base = None
    else:
        files = [f for f in ASR_DIR.glob("*.json") if not f.name.endswith((".meta.json", ".full.json"))]
    pat = f"cats_{rid}_v0_" if re.fullmatch(r"c\d\d", rid) else rid
    m = sorted(f for f in files if f.name.startswith(pat))
    if not m:
        raise FileNotFoundError(f"{rid} ({kind})")
    f = m[0]
    tr = json.loads(f.read_text(encoding="utf-8"))["transcript"]
    return Record(id=rid, utts=[Utt(i, u["speaker_id"], u["start_ms"], u["end_ms"], u["text"]) for i, u in enumerate(tr)])


def load_text_dialog(path: Path) -> Record:
    """bench_llm dialogs: lines '[мм:сс] Говорящий N: текст'."""
    utts = []
    for ln in path.read_text(encoding="utf-8").splitlines():
        m = re.match(r"\[(\d+):(\d+)\]\s*Говорящий\s+(\d+):\s*(.*)", ln)
        if m:
            t = (int(m[1]) * 60 + int(m[2])) * 1000
            utts.append(Utt(len(utts), f"speaker_{m[3]}", t, t + 4000, m[4]))
    for a, b in zip(utts, utts[1:]):
        a.end_ms = min(a.end_ms, b.start_ms)
    return Record(id=path.stem, utts=utts)


def fmt_time(ms: int) -> str:
    s = ms // 1000
    return f"{s // 60:02d}:{s % 60:02d}"


def cats_titles() -> dict[str, str]:
    p = HERE.parent / "datasets" / "stenodata" / "data"
    out = {}
    for f in ("cats_ru_1.txt", "cats_ru_2.txt"):
        path = p / f
        if not path.exists():
            path = ROOT / ".worktrees" / "datasets" / "src" / "eval" / "datasets" / "stenodata" / "data" / f
        for ln in path.read_text(encoding="utf-8").splitlines():
            m = re.match(r"## (c\d\d) \| (.*?) \| (\d+)", ln)
            if m:
                out[m[1]] = m[2]
    return out


def cats_scripts() -> dict[str, list[tuple[int, str]]]:
    """scenario id -> [(speaker N, text)]."""
    p = ROOT / ".worktrees" / "datasets" / "src" / "eval" / "datasets" / "stenodata" / "data"
    if (HERE.parent / "datasets" / "stenodata" / "data").exists():
        p = HERE.parent / "datasets" / "stenodata" / "data"
    out: dict[str, list[tuple[int, str]]] = {}
    cur = None
    for f in ("cats_ru_1.txt", "cats_ru_2.txt"):
        for ln in (p / f).read_text(encoding="utf-8").splitlines():
            m = re.match(r"## (c\d\d) \|", ln)
            if m:
                cur = m[1]
                out[cur] = []
                continue
            m = re.match(r"(\d)[!+]?: (.*)", ln)
            if m and cur:
                out[cur].append((int(m[1]), m[2]))
    return out


def overlap_ms(a: Utt, b: Utt) -> int:
    return max(0, min(a.end_ms, b.end_ms) - max(a.start_ms, b.start_ms))


def map_gold(ref: Record, asr: Record, ref_idx: list[int]) -> list[int]:
    """Pipeline utterances that overlap the gold reference utterances in time."""
    out = []
    for g in ref_idx:
        gu = ref.utts[g]
        for a in asr.utts:
            ov = overlap_ms(gu, a)
            if ov >= 0.3 * min(gu.end_ms - gu.start_ms, a.end_ms - a.start_ms) and ov > 200:
                out.append(a.idx)
    return sorted(set(out))
