"""Данные для хостовой проверки качества поиска Kotlin == Python на корпусе cats (hit@5, MRR) и скорости/памяти.
Пишет в $ROOT/.cache/search-eval/: eval.json (записи, вопросы, эталонные ответы Python) и бинарные векторы окон/вопросов.

  src/eval/bench_rag/.venv/bin/python src/android/scripts/gen_search_eval.py [--models user2-small rubert-tiny-turbo]
Python-эталон: bench_rag Index.search (RRF, soft-фильтр говорящего, k = 10, область записи/история как в run_retrieval), эмбеддинги -- OnnxEmbedder.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "src/eval/bench_rag"))
from bench_rag.chunker import chunk_corpus  # noqa: E402
from bench_rag.data import CACHE  # noqa: E402
from bench_rag.embed import make  # noqa: E402
from bench_rag.evalset import build_questions  # noqa: E402
from bench_rag.metrics import agg, score  # noqa: E402
from bench_rag.retrieval import Index  # noqa: E402

OUT = CACHE / "search-eval"


def rec_json(r):
    return {"id": r.id, "title": r.title or r.id, "names": r.names,
            "utts": [{"idx": u.idx, "speaker": u.speaker, "start_ms": u.start_ms, "end_ms": u.end_ms, "text": u.text} for u in r.utts]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="+", default=["user2-small", "rubert-tiny-turbo"])
    a = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    doc = {"corpora": {}, "python": {}}
    for kind in ("asr", "ref"):
        corpus, qs = build_questions(kind)
        qs = [q for q in qs if q.gold]
        doc["corpora"][kind] = {"records": [rec_json(r) for r in corpus.values()],
                                "questions": [{"id": q.id, "kind": q.kind, "record": q.record, "text": q.text, "gold": sorted([list(g) for g in q.gold])} for q in qs]}
        chunks = chunk_corpus(corpus, "win3s1")
        for name in a.models:
            emb = make(name, threads=4)
            vecs = emb.embed([c.embed_text for c in chunks], "doc")
            qv = {q.id: emb.embed([q.text], "query")[0] for q in qs}   # (запрос с очищенным говорящим эмбеддится в Kotlin; здесь как в run_retrieval -- по cleaned q внутри Index)
            idx = Index(chunks, corpus, vecs, stem_mode="snow5")
            cache = {}

            def qvec(q):
                if q not in cache:
                    cache[q] = emb.embed([q], "query")[0]
                return cache[q]
            rows, per = {"all": []}, {}
            for q in qs:
                rec = None if q.kind == "history" else q.record
                hits = idx.search(q.text, qvec, k=10, record=rec, speaker_mode="soft")
                s = score(q, hits)
                rows["all"].append(s)
                rows.setdefault(q.kind, []).append(s)
                per[q.id] = {"top": [f"{h.chunk.record}:{h.chunk.utts[0]}:{h.chunk.utts[-1]}" for h in hits], **s}
            doc["python"][f"{kind}/{name}"] = {"agg": {k: agg(v) for k, v in rows.items()}, "per_question": per, "chunks": len(chunks)}
            print(kind, name, doc["python"][f"{kind}/{name}"]["agg"]["all"], "chunks", len(chunks), flush=True)
            if kind == "asr":
                vecs.astype(np.float32).tofile(OUT / f"windows-{name}.f32")
                np.vstack([qv[q.id] for q in qs]).astype(np.float32).tofile(OUT / f"questions-{name}.f32")
                (OUT / f"windows-{name}.json").write_text(json.dumps([{"cid": c.cid, "record": c.record, "utts": c.utts} for c in chunks]))
            emb.close()
    (OUT / "eval.json").write_text(json.dumps(doc, ensure_ascii=False))
    print("->", OUT / "eval.json", (OUT / "eval.json").stat().st_size // 1024, "KiB")


if __name__ == "__main__":
    main()
