"""Boundary F1 of the embedding segmenter on the B50 documents: python -m bench_rag.run_segment --models rubert-tiny2 e5-small"""
import argparse
import json

import numpy as np

from .chunker import merge_turns
from .embed import make
from .segment import boundaries
from .sumdata import all_docs


def f1(pred, gold, tol):
    tp = sum(any(abs(p - g) <= tol for p in pred) for g in gold)
    pr = sum(any(abs(p - g) <= tol for g in gold) for p in pred)
    P = pr / len(pred) if pred else (1.0 if not gold else 0.0)
    R = tp / len(gold) if gold else 1.0
    return P, R, (2 * P * R / (P + R) if P + R else 0.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="+", default=["rubert-tiny2"])
    ap.add_argument("--k", type=int, nargs="+", default=[2, 3])
    ap.add_argument("--alpha", type=float, nargs="+", default=[0.0, 0.5, 1.0])
    ap.add_argument("--tol", type=int, default=1)
    ap.add_argument("--min-chars", type=int, nargs="+", default=[150])
    ap.add_argument("--min-utts", type=int, nargs="+", default=[3])
    a = ap.parse_args()
    docs = all_docs("asr")
    for m in a.models:
        if m == 'lexical':
            from .segment import lexical_vectors
            vecs = {d.id: lexical_vectors([u.text for u in d.rec.utts]) for d in docs}
            emb = None
        else:
            emb = make(m)
            vecs = {d.id: emb.embed([f"{u.text}" for u in d.rec.utts], "doc") for d in docs}
        for k in a.k:
            for al, mc, mu in [(x, y, z) for x in a.alpha for y in a.min_chars for z in a.min_utts]:
                P = R = F = n = 0
                ng = npred = 0
                for d in docs:
                    gold = [t.start for t in d.topics[1:]]
                    pred = boundaries(vecs[d.id], [len(u.text) + 20 for u in d.rec.utts], k=k, alpha=al, min_chars=mc, min_utts=mu, max_chars=2400)
                    p, r, f = f1(pred, gold, a.tol)
                    P += p; R += r; F += f; n += 1; ng += len(gold); npred += len(pred)
                print(json.dumps({"model": m, "k": k, "alpha": al, "mc": mc, "mu": mu, "P": round(P / n, 3), "R": round(R / n, 3), "F1": round(F / n, 3), "gold": ng, "pred": npred}), flush=True)
        if emb:
            emb.close()


if __name__ == "__main__":
    main()
