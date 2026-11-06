"""Длина чанка и временные метки на длинных записях.

python -m bench_asr.run_chunking --model gigaam3_rnnt --dataset <long_root> --modes 10,20,30,whole,seg --threads 4 --out results/chunk.jsonl
Режимы: N — VAD (max_speech=N) + упаковка соседних интервалов до N с; seg — каждый VAD-сегмент отдельно (<=25 с);
whole — вся запись одним куском (без VAD).
Метрики: WER(norm) по всем записям, RTF декодера (без VAD), число чанков, пик RAM процесса, ошибка границ реплик.
"""
from __future__ import annotations
import argparse, json, time, resource, sys, statistics
from pathlib import Path
import numpy as np
from .engines import Engine
from .registry import REGISTRY
from .data import load_dataset
from .vad import vad_segments, pack_chunks, SR
from .textnorm import wer_counts
from .words import words_from_tokens, utterance_bounds_error


def peak_mb():
    r = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return r / 2**20 if sys.platform == "darwin" else r / 1024


def chunks_for(x, mode):
    if mode == "whole":
        return [(0, len(x))]
    if mode == "seg":
        return vad_segments(x, max_speech=25.0)
    n = float(mode)
    return pack_chunks(vad_segments(x, max_speech=n), n, x=x)


def run_mode(eng, items, mode):
    S = [0, 0, 0, 0]; dec_s = 0.0; tot_audio = 0.0; nch = 0; errs_s, errs_e = [], []; per_rec = []; ts_ok = True
    for it in items:
        x = it.load_audio(); tot_audio += len(x) / SR
        chunks = chunks_for(x, mode)
        words = []; texts = []
        for a, b in chunks:
            h = eng.decode(x[a:b]); dec_s += h.wall_s; nch += 1
            texts.append(h.text)
            if h.tokens and h.timestamps and len(h.tokens) == len(h.timestamps):
                words += words_from_tokens(h.tokens, h.timestamps, offset=a / SR)
            elif h.text.strip():                 # пустой чанк (тишина) — не повод отключать метки у всей записи
                ts_ok = False
        text = " ".join(t for t in texts if t)
        c = wer_counts(it.ref_text, text, "norm"); S = [p + q for p, q in zip(S, c)]
        per_rec.append({"id": it.id, "condition": it.condition, "dur_s": len(x) / SR, "wer": (c[0] + c[1] + c[2]) / max(c[3], 1), "hyp": text})
        if words and ts_ok:
            for es, ee in utterance_bounds_error(it.ref, words):
                errs_s.append(es); errs_e.append(ee)
    out = {"mode": mode, "wer_norm": (S[0] + S[1] + S[2]) / max(S[3], 1), "S": S[0], "D": S[1], "I": S[2], "N": S[3],
           "rtf": dec_s / tot_audio, "n_chunks": nch, "audio_s": tot_audio, "peak_rss_mb": peak_mb(), "per_rec": per_rec}
    if errs_s:
        ae_s = np.abs(errs_s); ae_e = np.abs(errs_e)
        out.update(ts_n=len(errs_s), start_med_ms=float(np.median(ae_s) * 1000), end_med_ms=float(np.median(ae_e) * 1000),
                   start_p90_ms=float(np.percentile(ae_s, 90) * 1000), end_p90_ms=float(np.percentile(ae_e, 90) * 1000),
                   start_bias_ms=float(np.median(errs_s) * 1000), end_bias_ms=float(np.median(errs_e) * 1000),
                   within250=float(np.mean((ae_s < 0.25) & (ae_e < 0.25))))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True); ap.add_argument("--dataset", required=True)
    ap.add_argument("--modes", default="10,20,30,whole,seg"); ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--limit", type=int, default=None); ap.add_argument("--decoding", default="greedy_search")
    ap.add_argument("--split", default=None)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    items = load_dataset(a.dataset, split=a.split, limit=a.limit)
    eng = Engine(REGISTRY[a.model], a.threads, a.decoding)
    eng.decode(np.random.RandomState(0).randn(32000).astype(np.float32) * 0.01)
    for mode in a.modes.split(","):
        r = run_mode(eng, items, mode)
        r.update(model=a.model, threads=a.threads, decoding=a.decoding, dataset=str(a.dataset))
        print(json.dumps({k: (round(v, 4) if isinstance(v, float) else v) for k, v in r.items() if k != "per_rec"}, ensure_ascii=False), flush=True)
        with open(a.out, "a", encoding="utf-8") as f:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
