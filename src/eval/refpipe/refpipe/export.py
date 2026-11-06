"""Export: <id>.json (contract §1), <id>.meta.json, <id>.full.json (words, turns, chunks)."""
from __future__ import annotations

import json
import os
from pathlib import Path

from .pipeline import Result


def transcript_obj(res: Result) -> dict:
    return {"transcript": [
        {"speaker_id": u.speaker_id, "start_ms": int(u.start_ms), "end_ms": int(u.end_ms), "text": u.text.strip()}
        for u in res.utterances]}


def dumps(obj, indent=None) -> str:
    return json.dumps(obj, ensure_ascii=False, indent=indent, separators=(",", ":") if indent is None else None)


def _write(path: Path, text: str) -> None:
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, path)


def write_outputs(out_dir: Path, file_id: str, res: Result, meta: dict, full: bool = True) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    _write(out_dir / f"{file_id}.json", dumps(transcript_obj(res)))
    _write(out_dir / f"{file_id}.meta.json", dumps(meta, indent=2))
    if full:
        obj = {
            "tier": meta.get("tier"),
            "utterances": [{"speaker_id": u.speaker_id, "start_ms": u.start_ms, "end_ms": u.end_ms, "text": u.text,
                            "words": [{"text": w.text, "start_ms": w.start_ms, "end_ms": w.end_ms,
                                       "confidence": None if w.confidence is None else round(w.confidence, 4)}
                                      for w in u.words]}
                           for u in res.utterances],
            "turns": [[t.speaker, t.start_ms, t.end_ms] for t in res.turns],
            "speech": [[s.start_ms, s.end_ms] for s in res.speech],
            "chunks": [[c.start_ms, c.end_ms] for c in res.chunks],
        }
        _write(out_dir / f"{file_id}.full.json", dumps(obj))
