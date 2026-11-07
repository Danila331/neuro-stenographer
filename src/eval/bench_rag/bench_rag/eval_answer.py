"""B51 answer quality: python -m bench_rag.eval_answer --emb rubert-tiny2 --chunk win3s1 --top 3 --models 1.7b"""
from __future__ import annotations

import argparse
import json
import time

import numpy as np

from . import answer as A
from .chunker import chunk_corpus
from .embed import make
from .evalset import build_questions
from .llm import server
from .questions import ANS, NEG
from .retrieval import Index
from .run_retrieval import cached_embed
from .metrics import score


def norm(s):
    return s.lower().replace("ё", "е")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--emb", default="rubert-tiny2")
    ap.add_argument("--chunk", default="win3s1")
    ap.add_argument("--top", type=int, default=3)
    ap.add_argument("--models", nargs="+", default=["1.7b"])
    ap.add_argument("--corpus", default="asr")
    ap.add_argument("--tag", default="a")
    ap.add_argument("--prompt", default="search_answer_system.txt")
    ap.add_argument("--n-ctx", type=int, default=4096)
    a = ap.parse_args()
    corpus, qs = build_questions(a.corpus)
    chunks = chunk_corpus(corpus, a.chunk)
    emb = make(a.emb)
    vecs, _ = cached_embed(emb, [c.embed_text for c in chunks], "doc", f"{a.corpus}{a.chunk}{emb.path.name}")
    idx = Index(chunks, corpus, vecs)
    qv = lambda q: cached_embed(emb, [q], "query", "")[0][0]
    items = [(q.id, q.record if q.kind != "history" else None, q.text, q) for q in qs if q.id in ANS and q.gold]
    items += [(i, r, t, None) for i, r, t in NEG]
    # retrieval first (embedder busy), then free it and start the LLM
    ret = {}
    for qid, rec, text, q in items:
        ret[qid] = idx.search(text, qv, k=a.top, record=rec)
    emb.close()
    for m in a.models:
        rows = []
        with server(m, n_ctx=a.n_ctx) as srv:
            for qid, rec, text, q in items:
                hits = ret[qid]
                r = A.answer(srv, hits, corpus, text, prompt_file=a.prompt)
                ans: A.Answer = r["answer"]
                low = norm(ans.text)
                row = {"id": qid, "neg": q is None, "answer": ans.text, "refused": "в найденных фрагментах этого нет" in low, "cites_ok": len(ans.valid_cites), "cites_bad": ans.bad_cites,
                       "prompt_n": r["prompt_n"], "cache_n": r["cache_n"], "gen_n": r["predicted_n"], "pp_ms": r["prompt_ms"], "tg_ms": r["predicted_ms"]}
                if q is not None:
                    row["retrieved"] = score(q, hits)["hit5"]
                    row["correct"] = any(norm(k) in low for k in ANS[qid])
                    row["cited_gold"] = any((rid, u) in q.gold for rid, t, sp in ans.valid_cites for u in range(len(corpus[rid].utts)) if corpus[rid].utts[u].start_ms // 1000 == t and corpus[rid].utts[u].speaker == sp)
                rows.append(row)
                print(json.dumps(row, ensure_ascii=False), flush=True)
            peak = {"rss_mb": round(srv.peak_rss / 2**20)}
        pos = [r for r in rows if not r["neg"]]
        neg = [r for r in rows if r["neg"]]
        ok_ret = [r for r in pos if r["retrieved"]]
        summ = {
            "model": m, "n_pos": len(pos), "answer_correct": round(np.mean([r["correct"] for r in pos]), 3),
            "strict_ok": round(np.mean([r["correct"] and r["cited_gold"] and not r["refused"] for r in pos]), 3), "correct_when_retrieved": round(np.mean([r["correct"] for r in ok_ret]), 3), "retrieved": round(np.mean([r["retrieved"] for r in pos]), 3),
            "has_cite": round(np.mean([r["cites_ok"] + r["cites_bad"] > 0 for r in pos]), 3),
            "cite_valid": round(sum(r["cites_ok"] for r in pos + neg) / max(1, sum(r["cites_ok"] + r["cites_bad"] for r in pos + neg)), 3),
            "cites_gold": round(np.mean([r["cited_gold"] for r in pos]), 3), "neg_refused": f"{sum(r['refused'] for r in neg)}/{len(neg)}",
            "refused_pos": round(np.mean([r["refused"] for r in pos]), 3),
            "prompt_tok": round(np.mean([r["prompt_n"] + r["cache_n"] for r in rows])), "gen_tok": round(np.mean([r["gen_n"] for r in rows])),
            "s_per_q_pc": round(np.mean([(r["pp_ms"] + r["tg_ms"]) / 1000 for r in rows]), 1), "peak": peak,
        }
        print("SUMMARY", json.dumps(summ, ensure_ascii=False), flush=True)
        (A.PROMPTS.parent.parent / "results" / f"answer_{m}_{a.tag}.json").write_text(json.dumps({"summary": summ, "rows": rows}, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
