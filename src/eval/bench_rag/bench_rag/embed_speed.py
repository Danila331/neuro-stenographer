"""Latency / RAM of an embedder on this PC. python -m bench_rag.embed_speed --models e5-small ... ; each model in its own process.

Utterance = a single pipeline utterance (median ~8 words); chunk = a window of 3 turns with speaker labels (~40 words).
"""
from __future__ import annotations

import argparse
import json
import os
import resource
import subprocess
import sys
import time

import numpy as np
import psutil


def one(name: str, threads: int):
    from .chunker import chunk_corpus
    from .embed import make
    from .evalset import load_corpus
    corpus = load_corpus("asr")
    utt = [u.text for r in list(corpus.values())[:20] for u in r.utts][:80]
    chunks = [c.embed_text for c in chunk_corpus(dict(list(corpus.items())[:20]), "win3")][:40]
    t0 = time.perf_counter()
    emb = make(name, threads=threads)
    load_s = time.perf_counter() - t0
    emb.embed(utt[:3], "doc")
    out = {"model": name, "threads": threads, "load_s": round(load_s, 2), "dim": emb.dim}
    for label, texts in (("utt", utt), ("chunk", chunks)):
        ts = []
        for t in texts:
            a = time.perf_counter()
            emb.embed([t], "doc")
            ts.append((time.perf_counter() - a) * 1000)
        out[f"{label}_p50_ms"] = round(float(np.median(ts)), 1)
        out[f"{label}_p95_ms"] = round(float(np.percentile(ts, 95)), 1)
        out[f"{label}_chars"] = int(np.mean([len(t) for t in texts]))
    if hasattr(emb, "proc"):
        out["rss_mb"] = round(psutil.Process(emb.proc.pid).memory_info().rss / 2**20)
        out["peak_rss_mb"] = out["rss_mb"]
        emb.close()
    else:
        out["peak_rss_mb"] = round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 2**20)
        out["rss_mb"] = round(psutil.Process().memory_info().rss / 2**20)
    print(json.dumps(out), flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="+")
    ap.add_argument("--threads", type=int, nargs="+", default=[4, 1])
    ap.add_argument("--one", default="")
    a = ap.parse_args()
    if a.one:
        one(a.one, a.threads[0])
        return
    for m in a.models:
        for t in a.threads:
            r = subprocess.run([sys.executable, "-m", "bench_rag.embed_speed", "--one", m, "--threads", str(t)], capture_output=True, text=True)
            print(r.stdout.strip() or f"FAIL {m}: {r.stderr[-300:]}", flush=True)


if __name__ == "__main__":
    main()
