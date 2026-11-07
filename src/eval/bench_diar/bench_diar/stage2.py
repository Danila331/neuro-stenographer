"""Precompute stages A+B for all e2e cases (datasets x split) and several embedding models.
python -m bench_diar.stage2 --split dev --embs a,b --shift 0.3 --procs 4
"""
from __future__ import annotations

import argparse
import multiprocessing as mp
import time

from .data import load_audio
from .e2e import load_cases
from .pipeline import PCfg, Prepared


def _job(a):
    key, path, cfg = a
    t = time.time()
    Prepared(key.replace("/", "__"), load_audio(path), cfg)
    return key, cfg.emb, cfg.shift, time.time() - t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--split", default="dev")
    ap.add_argument("--datasets", default=None)
    ap.add_argument("--embs", required=True)
    ap.add_argument("--seg", default="pyannote3")
    ap.add_argument("--shift", type=float, default=0.3)
    ap.add_argument("--procs", type=int, default=4)
    a = ap.parse_args()
    cases = load_cases(a.datasets.split(",") if a.datasets else None, a.split)
    cases.sort(key=lambda c: -c.duration_ms)
    jobs = [(c.key, c.audio, PCfg(seg=a.seg, emb=e, shift=a.shift, exclude_overlap=True, threads=1))
            for e in a.embs.split(",") for c in cases]
    t0 = time.time()
    with mp.get_context("spawn").Pool(a.procs) as p:
        for i, r in enumerate(p.imap(_job, jobs)):
            print(f"[{i + 1}/{len(jobs)} {time.time() - t0:.0f}s] {r[1][-30:]} {r[0]} shift={r[2]} {r[3]:.0f}s", flush=True)


if __name__ == "__main__":
    main()
