"""B50 evaluation: python -m bench_rag.eval_summary --models 1.7b --tag v1 [--docs M1 d1_budget] [--no-grammar] [--static-refs]"""
from __future__ import annotations

import argparse
import json
import re
import time
from pathlib import Path

import numpy as np

from . import summary as S
from .llm import server
from .sumdata import Doc, all_docs, composite
from .text import terms

RES = Path(__file__).resolve().parent.parent / "results"


def norm(s: str) -> str:
    return s.lower().replace("ё", "е")


def topic_text(t: S.Topic) -> str:
    return norm(" ".join([t.title] + [p.text for p in t.points] + [x.text for x in t.decisions + t.tasks]))


def iou(a0, a1, b0, b1) -> float:
    i = max(0, min(a1, b1) - max(a0, b0) + 1)
    u = max(a1, b1) - min(a0, b0) + 1
    return i / u if u > 0 else 0.0


def score_doc(doc: Doc, topics: list[S.Topic]) -> dict:
    out: dict = {}
    n_gold = len(doc.topics)
    kw_need = 1 if doc.id.startswith("d") else 2
    cov_r = cov_k = cov_b = 0
    for g in doc.topics:
        cr = any(iou(g.start, g.end, t.start, t.end) >= 0.3 for t in topics)
        ck = any(sum(norm(k) in topic_text(t) for k in g.keywords) >= kw_need for t in topics)
        cb = any(iou(g.start, g.end, t.start, t.end) >= 0.3 and sum(norm(k) in topic_text(t) for k in g.keywords) >= 1 for t in topics)
        cov_r += cr
        cov_k += ck
        cov_b += cb
    out.update(topics_gold=n_gold, topics_gen=len(topics), cov_range=cov_r / n_gold, cov_kw=cov_k / n_gold, cov_both=cov_b / n_gold)
    # automatic speaker attribution: the cited speaker must be the speaker of the best-matching utterance of the topic
    ok = grounded = pts = 0
    for t in topics:
        for p in t.points:
            pts += 1
            a = set(terms(p.text))
            if not a:
                continue
            best, who = 0.0, set()
            for i in range(t.start, t.end + 1):
                sc = len(a & set(terms(doc.rec.utts[i].text))) / len(a)
                if sc > best + 1e-9:
                    best, who = sc, {doc.rec.utts[i].spk}
                elif abs(sc - best) < 1e-9 and sc > 0:
                    who.add(doc.rec.utts[i].spk)
            if best >= 0.2:
                grounded += 1
                ok += p.speaker in who
    out.update(points=pts, grounded=grounded / pts if pts else 0.0, attrib=ok / grounded if grounded else 0.0)
    # facts (decisions / tasks)
    items = [(x, "decision") for t in topics for x in t.decisions] + [(x, "task") for t in topics for x in t.tasks]
    fr, wk, wn = 0, 0, 0
    for f in doc.facts:
        m = [x for x, _ in items if any(norm(k) in norm(x.text) for k in f.any)]
        fr += bool(m)
        if f.who or f.who_speaker:
            wn += 1
            wk += any((f.who_speaker and f"говорящий {f.who_speaker}" in norm(x.who)) or any(norm(w) in norm(x.who + " " + x.text) for w in (f.who or [])) for x in m)
    out.update(facts=len(doc.facts), facts_recall=fr / len(doc.facts) if doc.facts else None, who_n=wn, who_ok=(wk / wn) if wn else None)
    # hand-written per-speaker claims (dialogs)
    if doc.claims:
        pts_all = [(p.speaker, norm(p.text)) for t in topics for p in t.points] + [(0, norm(x.text)) for t in topics for x in t.decisions + t.tasks]
        cov = mis = 0
        for s, kws in doc.claims:
            right = any(sp == s and any(norm(k) in tx for k in kws) for sp, tx in pts_all)
            wrong = any(sp not in (0, s) and any(norm(k) in tx for k in kws) for sp, tx in pts_all)
            cov += right
            mis += (not right) and wrong
        out.update(claims=len(doc.claims), claims_recall=cov / len(doc.claims), claims_misattr=mis / len(doc.claims))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="+", default=["1.7b"])
    ap.add_argument("--docs", nargs="*")
    ap.add_argument("--tag", default="t")
    ap.add_argument("--n-ctx", type=int, default=4096)
    ap.add_argument("--no-grammar", action="store_true")
    ap.add_argument("--min-support", type=float, default=0.3)
    ap.add_argument("--no-relink", action="store_true")
    ap.add_argument("--seg-model", default="rubert-tiny2", help="embedder for topic cuts ('none' = size-based cuts)")
    ap.add_argument("--target", type=int, default=1500, help="max fragment size, chars")
    ap.add_argument("--k", type=int, default=3)
    ap.add_argument("--alpha", type=float, default=0.0)
    ap.add_argument("--merge-sim", type=float, default=0.45)
    ap.add_argument("--merge-cos", type=float, default=None)
    ap.add_argument("--overview", action="store_true")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--flags", default=None, help="llama-server extra flags; default = phone flags (-nr, KV q8_0, fa)")
    a = ap.parse_args()
    docs = [d for d in all_docs("asr") if not a.docs or d.id in a.docs]
    vecs = {d.id: None for d in docs}
    if a.seg_model != "none":
        from .embed import make
        emb = make(a.seg_model)
        vecs = {d.id: emb.embed([u.text for u in d.rec.utts], "doc") for d in docs}
        emb.close()
    for m in a.models:
        rows = []
        extra = a.flags.split() if a.flags else None
        with server(m, n_ctx=a.n_ctx, threads=a.threads, extra=extra) as srv:
            for d in docs:
                t0 = time.perf_counter()
                r = S.summarize(srv, d.rec, vecs[d.id], n_ctx=a.n_ctx, use_grammar=not a.no_grammar, min_support=a.min_support, overview=a.overview,
                                target_chars=a.target, relink=not a.no_relink, merge_sim=a.merge_sim, merge_cos=a.merge_cos,
                                seg_kw={"k": a.k, "alpha": a.alpha})
                wall = time.perf_counter() - t0
                sc = score_doc(d, r["topics"])
                st: S.Stats = r["stats"]
                sc.update(doc=d.id, model=m, wall_s=round(wall, 1), frags=r["fragments"],
                          prompt_tok=sum(c["prompt_n"] for c in r["calls"]), cached_tok=sum(c["cache_n"] for c in r["calls"]), raw_topics=r["raw_topics"], relinked=st.relinked, gen_tok=sum(c["predicted_n"] for c in r["calls"]),
                          pp_s=round(sum(c["prompt_ms"] for c in r["calls"]) / 1000, 1), tg_s=round(sum(c["predicted_ms"] for c in r["calls"]) / 1000, 1),
                          truncated=sum(c["stop"] != "eos" and c["stop"] != "word" for c in r["calls"]),
                          refs=st.refs_total, ref_bad=st.refs_bad_range + st.refs_bad_speaker, items=st.items_total, dropped=st.items_dropped,
                          parse_failed=st.parse_failed, repaired=st.repaired, utts=len(d.rec.utts))
                sc["json"] = S.to_json(d.rec, r["topics"], r["overview"])
                rows.append(sc)
                print(json.dumps({k: (round(v, 2) if isinstance(v, float) else v) for k, v in sc.items() if k != "json"}, ensure_ascii=False), flush=True)
            peak = {"rss_mb": round(srv.peak_rss / 2**20), "uss_mb": round(srv.peak_uss / 2**20), "load_s": round(srv.load_s, 1)}
        RES.mkdir(exist_ok=True)
        (RES / f"summary_{m}_{a.tag}.json").write_text(json.dumps({"args": vars(a), "peak": peak, "rows": rows}, ensure_ascii=False, indent=1))
        agg = {k: round(float(np.mean([r[k] for r in rows if r.get(k) is not None])), 3) for k in
               ("cov_range", "cov_kw", "cov_both", "grounded", "attrib", "facts_recall", "who_ok", "claims_recall", "claims_misattr", "wall_s")
               if any(r.get(k) is not None for r in rows)}
        tot = lambda k: sum(r[k] for r in rows)
        agg.update(ref_valid_raw=round(1 - tot("ref_bad") / max(1, tot("refs")), 3), dropped=round(tot("dropped") / max(1, tot("items")), 3),
                   parse_failed=tot("parse_failed"), repaired=tot("repaired"), peak=peak)
        print("AGG", m, a.tag, json.dumps(agg, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
