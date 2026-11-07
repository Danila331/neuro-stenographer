"""Final e2e evaluation of diarization variants with the reference implementation (spkphrase.py).

python -m bench_diar.final_eval --split dev --methods base,phrase,hybrid --emb <name> [--turn-emb <name>]
Turns for 'base'/'hybrid' come from the STOCK sherpa OfflineSpeakerDiarization (runner.py, disk cache).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import time
from dataclasses import replace

import numpy as np

from refpipe.align import AlignParams
from refpipe.types import SpeakerTurn

from .data import Item, load_audio
from .e2e import RUNS, Case, fmt, load_cases, score_case, summarize
from .phrase import Extractor
from .pipeline import STAGE_CACHE
from .runner import Cfg, run_items
from .spkphrase import Params, embed_units, label_units, make_units


def stock_turns(cases: list[Case], cfg: Cfg, procs: int) -> dict[str, list[SpeakerTurn]]:
    items = [Item(c.key.replace("/", "__"), c.audio, [], c.duration_ms / 1000, c.nspk, c.condition, c.split, "") for c in cases]
    runs = run_items(cfg, items, procs)
    out = {}
    for c, r in zip(cases, runs):
        out[c.key] = [SpeakerTurn(int(s[3:]), int(round(a * 1000)), int(round(b * 1000))) for s, a, b in r["segs"]]
    return out


def phrase_labels(c: Case, turns, ex: Extractor, p: Params):
    words = [(w.start_ms, w.end_ms) for w in c.words]
    tt = [(t.speaker, t.start_ms, t.end_ms) for t in turns] if turns else None
    units = make_units(words, tt, p)
    if not units:
        return [], 0.0
    t = time.perf_counter()
    h = hashlib.md5((c.key + ex.emb + f"{p.pad_ms}|{p.embed_max_s}|" + str([(u[0], u[1]) for u in units])).encode()).hexdigest()[:16]
    f = STAGE_CACHE / f"S_{h}.npy"
    if f.exists():
        E = np.load(f)
    else:
        E = embed_units(load_audio(c.audio), units, ex, p)
        STAGE_CACHE.mkdir(parents=True, exist_ok=True)
        np.save(f, E)
    dt = time.perf_counter() - t
    dur = np.array([u[1] - u[0] for u in units])
    lab = label_units(E, dur, p)
    return [SpeakerTurn(int(l), u[0], u[1]) for u, l in zip(units, lab)], dt


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--split", default="dev")
    ap.add_argument("--base-run", default=None)
    ap.add_argument("--methods", default="base,phrase,hybrid")
    ap.add_argument("--emb", default="3dspeaker_speech_campplus_sv_zh-cn_16k-common")
    ap.add_argument("--turn-emb", default="3dspeaker_speech_campplus_sv_zh-cn_16k-common")
    ap.add_argument("--turn-th", type=float, default=0.5)
    ap.add_argument("--turn-shift", type=float, default=1.0)
    ap.add_argument("--gap", type=int, default=200)
    ap.add_argument("--max-gap", type=int, default=1500)
    ap.add_argument("--procs", type=int, default=2)
    ap.add_argument("--json-out", default=None)
    a = ap.parse_args()
    cases = load_cases(split=a.split, base_run=a.base_run or ("refpipe-medium-rescue" if a.split == "dev" else "diar-bench-test-asr"))
    print(len(cases), "cases")
    params = AlignParams(max_gap_ms=a.max_gap)
    p = Params(gap_ms=a.gap)
    res = {}
    ex = Extractor(a.emb, 1)
    need_turns = any(m in a.methods for m in ("base", "hybrid"))
    if need_turns:
        base_cfg = Cfg(emb=a.turn_emb, threshold=0.85, min_on=0.2, min_off=0.3, shift=0.1, threads=2)
        if "base" in a.methods:
            base_turns = ({c.key: c.base_turns for c in cases} if a.split == "dev" and not a.base_run
                          else stock_turns(cases, base_cfg, a.procs))
        hyb_cfg = Cfg(emb=a.turn_emb, threshold=a.turn_th, min_on=0.2, min_off=0.3, shift=a.turn_shift, threads=2)
        hyb_turns = stock_turns(cases, hyb_cfg, a.procs) if "hybrid" in a.methods else None
    for m in a.methods.split(","):
        rows, emb_s = [], 0.0
        for c in cases:
            if m == "base":
                turns = base_turns[c.key]
            elif m == "phrase":
                turns, dt = phrase_labels(c, None, ex, p)
                emb_s += dt
            elif m == "hybrid":
                turns, dt = phrase_labels(c, hyb_turns[c.key], ex, p)
                emb_s += dt
            rows.append(score_case(c, turns, params, with_cpwer=True))
        S = summarize(rows)
        res[m] = S
        print(f"== {m} (emb={a.emb[-34:]} gap={a.gap} align.max_gap={a.max_gap} split={a.split})")
        for k in ("all", "ru-dialog-synth", "ru-dialog-tts-cats", "en-dialog-synth", "ru-en-mixed"):
            if k in S:
                print(f"  {k:20s}", fmt(S[k]))
        print(f"  unit-embedding time {emb_s:.0f}s for {sum(c.duration_ms for c in cases) / 1000:.0f}s audio", flush=True)
    if a.json_out:
        json.dump(res, open(a.json_out, "w"), indent=1)


if __name__ == "__main__":
    main()
