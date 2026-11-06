"""Короткие реплики: точное совпадение, пустой вывод, потеря на VAD.

python -m bench_asr.run_short --model gigaam3_rnnt --short <asr-bench-short-tmp> --cats <ru-dialog-tts-cats> --out results/short.jsonl
Наборы: tts — синтез Piper (20 слов x 4 голоса); cats — реплики <= 2 слов, вырезанные из диалогов (+-150 мс).
Режимы: raw (клип целиком в ASR) и vad (Silero VAD threshold=0.45, min_speech=0.12 с -> ASR по сегментам; нет сегмента => пусто).
"""
from __future__ import annotations
import argparse, json
import numpy as np
from .engines import Engine
from .registry import REGISTRY
from .data import load_dataset
from .vad import vad_segments, pack_chunks, SR
from .textnorm import norm


def clips(short_root, cats_root):
    out = []
    for it in load_dataset(short_root):
        out.append(("tts", it.id, it.load_audio(), it.ref[0]["text"]))
    for it in load_dataset(cats_root):
        x = it.load_audio()
        for k, u in enumerate(it.ref):
            if len(u["text"].split()) <= 2:
                a, b = max(0, int((u["start_ms"] - 150) * SR / 1000)), min(len(x), int((u["end_ms"] + 150) * SR / 1000))
                out.append(("cats", f"{it.id}#{k}", x[a:b], u["text"]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True); ap.add_argument("--short", required=True); ap.add_argument("--cats", required=True)
    ap.add_argument("--threads", type=int, default=4); ap.add_argument("--out", required=True)
    a = ap.parse_args()
    eng = Engine(REGISTRY[a.model], a.threads)
    stats = {}
    rows = []
    for src, id_, x, ref in clips(a.short, a.cats):
        nref = norm(ref)
        raw_txt = eng.decode(x).text
        pad = np.zeros(int(0.5 * SR), np.float32)           # как в приложении: контекст вокруг реплики
        segs = vad_segments(np.concatenate([pad, x, pad]) if src == "cats" else x, threshold=0.45)
        xx = np.concatenate([pad, x, pad]) if src == "cats" else x
        vad_txt = " ".join(t for t in (eng.decode(xx[s:e]).text for s, e in pack_chunks(segs, 25.0, min_s=0.0, x=xx)) if t)
        for mode, txt in (("raw", raw_txt), ("vad", vad_txt)):
            s = stats.setdefault((src, mode), {"n": 0, "exact": 0, "empty": 0, "contains": 0})
            s["n"] += 1; s["exact"] += norm(txt) == nref; s["empty"] += norm(txt) == ""; s["contains"] += nref in norm(txt)
        rows.append({"src": src, "id": id_, "ref": ref, "raw": raw_txt, "vad": vad_txt, "vad_segments": len(segs)})
    res = {"model": a.model, "stats": {f"{k[0]}/{k[1]}": v for k, v in stats.items()}}
    print(json.dumps(res, ensure_ascii=False))
    with open(a.out, "a", encoding="utf-8") as f:
        f.write(json.dumps({**res, "rows": rows}, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
