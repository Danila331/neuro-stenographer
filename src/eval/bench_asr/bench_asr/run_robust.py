"""Галлюцинации на не-речи (тишина, шумы, синтетическое мяуканье/мурлыканье, щелчки).

python -m bench_asr.run_robust --model whisper_turbo_int8 --threads 4 --out results/robust.jsonl [--sec 30 --seeds 3]
Два режима для каждого клипа:
  raw — весь клип целиком в ASR (худший случай: VAD пропустил)
  vad — как в приложении: Silero VAD (threshold 0.45) -> чанки -> ASR
Метрика: число выданных слов на клип; доля клипов с непустым выводом; примеры.
"""
from __future__ import annotations
import argparse, json
import numpy as np
from .engines import Engine
from .registry import REGISTRY
from .synth import make_all, esc50_clips
from .vad import vad_segments, pack_chunks, SR


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True); ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--sec", type=float, default=30); ap.add_argument("--seeds", type=int, default=3)
    ap.add_argument("--esc50", default=None, help="путь к ESC-50 (папка с audio/ и meta/): реальные звуки, в т.ч. кошки")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    eng = Engine(REGISTRY[a.model], a.threads)
    clips = make_all(a.sec, tuple(range(a.seeds)))
    if a.esc50:
        clips.update(esc50_clips(a.esc50, a.sec, tuple(range(a.seeds))))
    rows = []
    for name, x in clips.items():
        kind = name.split("#")[0]
        h = eng.decode(x)
        segs = vad_segments(x, threshold=0.45)
        chunks = pack_chunks(segs, 25.0, x=x)
        vt = " ".join(t for t in (eng.decode(x[s:e]).text for s, e in chunks) if t)
        rows.append({"model": a.model, "clip": name, "kind": kind, "raw_text": h.text, "raw_words": len(h.text.split()),
                     "vad_text": vt, "vad_words": len(vt.split()), "vad_speech_s": sum(e - s for s, e in segs) / SR})
        print(name, "| raw:", h.text[:70], "| vad:", vt[:70], flush=True)
    with open(a.out, "a", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
