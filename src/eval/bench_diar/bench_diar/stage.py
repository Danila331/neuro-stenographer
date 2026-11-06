"""Precompute pipeline stages A+B (segmentation labels, chunk-speaker embeddings) for many models.

python -m bench_diar.stage --dataset DIR --embs a,b,c --shift 0.3 --procs 4 [--split dev]
Order: model-major so that early models finish first.
"""
from __future__ import annotations

import argparse
import multiprocessing as mp
import sys
import time

from .data import load_audio, load_dataset
from .pipeline import PCfg, Prepared


def _job(a):
    item, cfg = a
    t = time.time()
    Prepared(item.id, load_audio(item.audio), cfg)
    return item.id, cfg.emb, cfg.shift, time.time() - t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", required=True)
    ap.add_argument("--split", default=None)
    ap.add_argument("--seg", default="pyannote3")
    ap.add_argument("--int8", default="1")
    ap.add_argument("--embs", required=True)
    ap.add_argument("--shift", type=float, default=0.1)
    ap.add_argument("--exclude-overlap", default="1")
    ap.add_argument("--procs", type=int, default=4)
    ap.add_argument("--threads", type=int, default=1)
    a = ap.parse_args()
    items = load_dataset(a.dataset, split=a.split)
    items.sort(key=lambda i: -i.duration)
    jobs = [(it, PCfg(seg=a.seg, seg_int8=a.int8 == "1", emb=e, shift=a.shift,
                      exclude_overlap=a.exclude_overlap == "1", threads=a.threads))
            for e in a.embs.split(",") for it in items]
    ctx = mp.get_context("spawn")
    with ctx.Pool(a.procs) as p:
        for r in p.imap(_job, jobs):
            print(f"done {r[1][-34:]} {r[0]} shift={r[2]} {r[3]:.0f}s", flush=True)


if __name__ == "__main__":
    main()
