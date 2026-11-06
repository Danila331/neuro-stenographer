"""Проверка датасета на соответствие ARCHITECTURE §1–§2."""
from __future__ import annotations

import csv
import json
import re
import sys
from pathlib import Path

import soundfile as sf

from .common import OUR_DATASETS, SR, data_root

SPK_RE = re.compile(r"^speaker_[1-9][0-9]*$")
CONDITIONS = {"clean", "noise", "reverb", "overlap", "phone", "real", "farfield"}
REQUIRED = ["id", "audio", "ref", "duration_ms", "num_speakers", "lang", "source", "condition", "split"]


def check_ref(obj, duration_ms: int, n_speakers: int | None = None) -> list[str]:
    """Правила ARCHITECTURE §1. Возвращает список нарушений."""
    errs = []
    if not isinstance(obj, dict) or list(obj) != ["transcript"] or not isinstance(obj["transcript"], list):
        return ["top-level must be exactly {'transcript': [...]}"]
    first_seen: list[str] = []
    prev = None
    for i, u in enumerate(obj["transcript"]):
        if not isinstance(u, dict) or set(u) != {"speaker_id", "start_ms", "end_ms", "text"}:
            errs.append(f"[{i}] keys must be exactly speaker_id,start_ms,end_ms,text")
            continue
        sid, s, e, t = u["speaker_id"], u["start_ms"], u["end_ms"], u["text"]
        if not (isinstance(sid, str) and SPK_RE.match(sid)):
            errs.append(f"[{i}] bad speaker_id {sid!r}")
        elif sid not in first_seen:
            first_seen.append(sid)
            if sid != f"speaker_{len(first_seen)}":
                errs.append(f"[{i}] speakers must be numbered by first appearance, got {sid} as #{len(first_seen)}")
        if not (type(s) is int and type(e) is int):
            errs.append(f"[{i}] start_ms/end_ms must be int")
            continue
        if not (0 <= s < e <= duration_ms):
            errs.append(f"[{i}] times out of range: {s}..{e} (duration {duration_ms})")
        if not (isinstance(t, str) and t and t == t.strip()):
            errs.append(f"[{i}] bad text {t!r}")
        if prev is not None and (s, e) < prev:
            errs.append(f"[{i}] not sorted by (start_ms, end_ms)")
        prev = (s, e)
    if n_speakers is not None and len(first_seen) != n_speakers and obj["transcript"]:
        errs.append(f"num_speakers={n_speakers} but ref has {len(first_seen)}")
    return errs


def validate_dataset(root: Path, strict_dur: bool = True) -> list[str]:
    errs = []
    man = root / "manifest.csv"
    if not man.exists():
        return [f"{root}: no manifest.csv"]
    with open(man, encoding="utf-8", newline="") as f:
        rd = csv.DictReader(f)
        missing = [c for c in REQUIRED if c not in (rd.fieldnames or [])]
        if missing:
            return [f"{root}: manifest missing columns {missing}"]
        rows = list(rd)
    ids = set()
    for r in rows:
        i = r["id"]
        if i in ids:
            errs.append(f"{i}: duplicate id")
        ids.add(i)
        if r["condition"] not in CONDITIONS:
            errs.append(f"{i}: bad condition {r['condition']}")
        if r["split"] not in ("dev", "test"):
            errs.append(f"{i}: bad split {r['split']}")
        ap, rp = root / r["audio"], root / r["ref"]
        if r.get("annotated") == "0" and not rp.exists():
            if not ap.exists():
                errs.append(f"{i}: missing audio")
            continue  # реальная запись ещё не размечена — это допустимо
        if not ap.exists() or not rp.exists():
            errs.append(f"{i}: missing audio/ref file")
            continue
        info = sf.info(str(ap))
        if info.samplerate != SR or info.channels != 1 or info.subtype != "PCM_16" or info.format != "WAV":
            errs.append(f"{i}: audio must be 16 kHz mono PCM16 WAV, got {info.samplerate}/{info.channels}/{info.subtype}/{info.format}")
        dur_ms = int(round(info.frames * 1000 / info.samplerate))
        if abs(dur_ms - int(r["duration_ms"])) > 1:
            errs.append(f"{i}: manifest duration {r['duration_ms']} != actual {dur_ms}")
        obj = json.loads(rp.read_text(encoding="utf-8"))
        if "\\u" in rp.read_text(encoding="utf-8"):
            errs.append(f"{i}: ref contains \\u escapes")
        nsp = int(r["num_speakers"]) if r["num_speakers"] else None
        errs += [f"{i}: {e}" for e in check_ref(obj, dur_ms, nsp)]
    return errs


def main(argv=None):
    names = list(argv) if argv else (sys.argv[1:] if argv is None else []) or [n for n in OUR_DATASETS if (data_root() / n / "manifest.csv").exists()]
    bad = 0
    for n in names:
        root = Path(n) if Path(n).exists() else data_root() / n
        errs = validate_dataset(root)
        print(f"{root.name}: {'OK' if not errs else str(len(errs)) + ' problems'}")
        for e in errs[:30]:
            print("   ", e)
        bad += len(errs)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
