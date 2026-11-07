"""Retrieval benchmark: python -m bench_rag.run_retrieval --models e5-small ... --chunk utt turn win4 --corpus ref asr

Doc/query vectors are cached in .cache/rag/ (keyed by model, chunking, corpus) so ablations are cheap.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import time

import numpy as np

from .chunker import chunk_corpus
from .data import CACHE
from .embed import make
from .evalset import build_questions
from .metrics import agg, score
from .retrieval import Index

VC = CACHE / "rag"
VC.mkdir(exist_ok=True)


def cached_embed(emb, texts: list[str], kind: str, tag: str) -> tuple[np.ndarray, float]:
    key = hashlib.md5(("\n".join(texts) + kind + emb.name + tag).encode()).hexdigest()[:16]
    p = VC / f"{emb.name}-{kind}-{key}.npy"
    if p.exists():
        return np.load(p), 0.0
    t0 = time.perf_counter()
    v = emb.embed(texts, kind)
    dt = time.perf_counter() - t0
    np.save(p, v)
    return v, dt


def evaluate(corpus, qs, chunks, emb, *, tag, scope_all=False, **kw) -> dict:
    vecs = None
    t_doc = 0.0
    if emb is not None:
        vecs, t_doc = cached_embed(emb, [c.embed_text for c in chunks], "doc", tag)
    idx = Index(chunks, corpus, vecs, stem_mode=kw.pop("stem_mode", "snow5"))
    qcache: dict[str, np.ndarray] = {}

    def qvec(q):
        if q not in qcache:
            qcache[q] = cached_embed(emb, [q], "query", "")[0][0]
        return qcache[q]

    rows = {"all": [], "speaker": [], "name": [], "topic": [], "history": []}
    per_q = {}
    for q in qs:
        if not q.gold:
            continue
        rec = None if (scope_all or q.kind == "history") else q.record
        hits = idx.search(q.text, qvec if emb else None, k=10, record=rec, **kw)
        s = score(q, hits)
        per_q[q.id] = s["hit5"]
        rows["all"].append(s)
        rows[q.kind].append(s)
    out = {k: agg(v) for k, v in rows.items() if v}
    out["n"] = len(rows["all"])
    out["chunks"] = len(chunks)
    out["t_doc_s"] = round(t_doc, 1)
    out["miss"] = [k for k, v in per_q.items() if not v]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="*", default=[])
    ap.add_argument("--chunk", nargs="+", default=["utt"])
    ap.add_argument("--corpus", nargs="+", default=["ref"])
    ap.add_argument("--fusion", default="rrf")
    ap.add_argument("--alpha", type=float, default=0.5)
    ap.add_argument("--speaker", default="hard")
    ap.add_argument("--stem", default="snow5")
    ap.add_argument("--scope-all", action="store_true")
    ap.add_argument("--bm25-only", action="store_true")
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    res = []
    for ck in a.corpus:
        corpus, qs = build_questions(ck)
        for ch in a.chunk:
            chunks = chunk_corpus(corpus, ch)
            if a.bm25_only:
                r = evaluate(corpus, qs, chunks, None, tag=f"{ck}{ch}", dense=False, speaker_mode=a.speaker, stem_mode=a.stem, scope_all=a.scope_all)
                res.append({"model": "bm25", "corpus": ck, "chunk": ch, **r})
                print(json.dumps({k: v for k, v in res[-1].items() if k != "miss"}, ensure_ascii=False), flush=True)
            for m in a.models:
                emb = make(m)
                try:
                    for mode, kw in (("hybrid", {}), ("dense", {"bm25": False})):
                        r = evaluate(corpus, qs, chunks, emb, tag=f"{ck}{ch}{emb.path.name if hasattr(emb, 'path') else ''}", fusion=a.fusion, alpha=a.alpha,
                                     speaker_mode=a.speaker, stem_mode=a.stem, scope_all=a.scope_all, **kw)
                        res.append({"model": m, "mode": mode, "corpus": ck, "chunk": ch, **r})
                        print(json.dumps({k: v for k, v in res[-1].items() if k != "miss"}, ensure_ascii=False), flush=True)
                finally:
                    emb.close()
    if a.out:
        open(a.out, "w").write(json.dumps(res, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
