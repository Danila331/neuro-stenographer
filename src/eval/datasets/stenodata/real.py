"""Реальные записи пользователя: импорт аудио, черновик эталона из гипотезы, сборка эталона из ручной разметки.

Формат ручной разметки (`annotation/<id>.txt`), по одной реплике в строке:

    [MM:SS.mmm - MM:SS.mmm] <метка>: текст реплики

Метка — любая (A, B, Иван, speaker_2); при сборке метки нумеруются speaker_1..N по порядку первого
появления. Строки, начинающиеся с `#`, — комментарии. Перекрывающиеся реплики допустимы (две строки с
пересекающимися временами).
"""
from __future__ import annotations

import csv
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
import soundfile as sf

from .common import MANIFEST_COLS, SR, data_root, read_audio_16k, renumber_speakers, stable_split, write_ref, write_wav16
from .validate import check_ref

REAL_COLS = MANIFEST_COLS + ["annotated", "notes"]
LINE_RE = re.compile(r"^\[\s*(\d+):(\d{2})(?:\.(\d{1,3}))?\s*-\s*(\d+):(\d{2})(?:\.(\d{1,3}))?\s*\]\s*([^:]+?)\s*:\s*(.+?)\s*$")


def real_dir(name: str = "real") -> Path:
    return data_root() / name


def fmt_ts(ms: int) -> str:
    m, rem = divmod(int(ms), 60_000)
    return f"{m:02d}:{rem // 1000:02d}.{rem % 1000:03d}"


def _ts(m: str, s: str, frac: str | None) -> int:
    f = int((frac or "0").ljust(3, "0")[:3])
    return int(m) * 60_000 + int(s) * 1000 + f


def decode_to_16k(src: Path, dst: Path) -> None:
    """Любой формат -> WAV 16 кГц mono PCM16. Сначала ffmpeg (системный/из imageio-ffmpeg), иначе soundfile."""
    from .audio import ffmpeg_exe
    try:
        ff = shutil.which("ffmpeg") or ffmpeg_exe()
    except Exception:  # noqa: BLE001
        ff = None
    dst.parent.mkdir(parents=True, exist_ok=True)
    if ff:
        r = subprocess.run([ff, "-v", "error", "-y", "-i", str(src), "-vn", "-ac", "1", "-ar", str(SR),
                            "-c:a", "pcm_s16le", str(dst)], capture_output=True)
        if r.returncode == 0 and dst.exists():
            return
        print(f"ffmpeg failed for {src}: {r.stderr[-200:]!r}; trying soundfile", file=sys.stderr)
    write_wav16(dst, read_audio_16k(src))


def _read_manifest(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def _write_manifest(path: Path, rows: list[dict]):
    cols = list(REAL_COLS)
    for r in rows:
        for k in r:
            if k not in cols:
                cols.append(k)
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        for r in sorted(rows, key=lambda r: r["id"]):
            w.writerow({c: r.get(c, "") for c in cols})


def import_audio(files: list[Path], name: str = "real", split: str | None = None, num_speakers: int | None = None,
                 notes: str = "") -> list[str]:
    """Копирует исходники в audio_orig/, конвертирует в audio/<id>.wav, добавляет строки в manifest.csv."""
    root = real_dir(name)
    (root / "audio").mkdir(parents=True, exist_ok=True)
    (root / "audio_orig").mkdir(exist_ok=True)
    (root / "ref").mkdir(exist_ok=True)
    (root / "annotation").mkdir(exist_ok=True)
    rows = {r["id"]: r for r in _read_manifest(root / "manifest.csv")}
    ids = []
    for src in files:
        src = Path(src)
        rid = re.sub(r"[^0-9A-Za-zА-Яа-я_.-]+", "_", src.stem).strip("_.") or "rec"
        shutil.copy2(src, root / "audio_orig" / (rid + src.suffix.lower()))
        decode_to_16k(src, root / "audio" / f"{rid}.wav")
        info = sf.info(str(root / "audio" / f"{rid}.wav"))
        rows[rid] = dict(id=rid, audio=f"audio/{rid}.wav", ref=f"ref/{rid}.json",
                         duration_ms=int(round(info.frames * 1000 / info.samplerate)),
                         num_speakers=num_speakers if num_speakers else "", lang="ru", source="real",
                         condition="real", split=split or stable_split(rid), annotated=rows.get(rid, {}).get("annotated", "0"),
                         notes=notes)
        ids.append(rid)
    _write_manifest(root / "manifest.csv", list(rows.values()))
    return ids


def hypothesis_to_draft(hyp_json: Path, rid: str, name: str = "real", merge_gap_ms: int = 0) -> Path:
    """Из гипотезы пайплайна (формат выхода) делает черновик: ref/<id>.draft.json и annotation/<id>.txt."""
    root = real_dir(name)
    obj = json.loads(Path(hyp_json).read_text(encoding="utf-8"))
    utts = obj["transcript"] if isinstance(obj, dict) else obj
    utts = renumber_speakers([dict(u) for u in utts])
    (root / "ref").mkdir(parents=True, exist_ok=True)
    (root / "annotation").mkdir(parents=True, exist_ok=True)
    (root / "ref" / f"{rid}.draft.json").write_text(
        json.dumps({"transcript": utts}, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    dur = next((int(r["duration_ms"]) for r in _read_manifest(root / "manifest.csv") if r["id"] == rid), None)
    lines = [f"# id: {rid}" + (f"   duration_ms: {dur}" if dur else ""),
             "# ЧЕРНОВИК из гипотезы пайплайна — проверьте на слух: текст, границы, говорящих, пропущенные реплики.",
             "# Формат: [MM:SS.mmm - MM:SS.mmm] метка: текст   (метка любая; перекрытия — отдельными строками)",
             "# После правки: python real/compile_ref.py " + rid, ""]
    for u in utts:
        lines.append(f"[{fmt_ts(u['start_ms'])} - {fmt_ts(u['end_ms'])}] {u['speaker_id']}: {u['text']}")
    out = root / "annotation" / f"{rid}.txt"
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return out


def parse_annotation(text: str) -> list[dict]:
    utts = []
    for n, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        m = LINE_RE.match(line)
        if not m:
            raise ValueError(f"line {n}: cannot parse {raw!r}; expected '[MM:SS.mmm - MM:SS.mmm] метка: текст'")
        s = _ts(*m.group(1, 2, 3))
        e = _ts(*m.group(4, 5, 6))
        utts.append({"speaker_id": m.group(7).strip(), "start_ms": s, "end_ms": e, "text": m.group(8).strip()})
    return utts


def compile_ref(rid: str, name: str = "real") -> Path:
    """annotation/<id>.txt -> ref/<id>.json (+ проверка правил и annotated=1 в manifest)."""
    root = real_dir(name)
    utts = parse_annotation((root / "annotation" / f"{rid}.txt").read_text(encoding="utf-8"))
    rows = _read_manifest(root / "manifest.csv")
    row = next((r for r in rows if r["id"] == rid), None)
    if row is None:
        raise KeyError(f"{rid} not in {root/'manifest.csv'}; run import first")
    dur = int(row["duration_ms"])
    ref = {"transcript": renumber_speakers(utts)}
    errs = check_ref(ref, dur)
    if errs:
        raise ValueError("annotation errors:\n  " + "\n  ".join(errs))
    write_ref(root / "ref" / f"{rid}.json", ref["transcript"])
    row["annotated"] = "1"
    row["num_speakers"] = str(len({u["speaker_id"] for u in ref["transcript"]}))
    _write_manifest(root / "manifest.csv", rows)
    return root / "ref" / f"{rid}.json"
