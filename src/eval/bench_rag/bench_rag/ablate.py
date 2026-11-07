"""Retrieval ablations with a fixed embedder: python -m bench_rag.ablate --model user2-small"""
import argparse
import json

from .chunker import chunk_corpus
from .embed import make
from .evalset import build_questions
from .run_retrieval import evaluate


def row(tag, r):
    a = r["all"]
    print(f"| {tag} | {a['hit1']} | {a['hit5']} | {a['recall5']} | {a['mrr']} | {r.get('speaker', {}).get('hit5', '-')} | {r.get('name', {}).get('hit5', '-')} | {r.get('topic', {}).get('hit5', '-')} | {r.get('history', {}).get('hit5', '-')} |", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="user2-small")
    ap.add_argument("--corpus", default="asr")
    a = ap.parse_args()
    corpus, qs = build_questions(a.corpus)
    emb = make(a.model)
    tag = lambda ch: f"{a.corpus}{ch}{emb.path.name}"
    print("| config | hit@1 | hit@5 | recall@5 | MRR | speaker | name | topic | history |")
    ch = chunk_corpus(corpus, "turn")
    for label, kw in [("turn hybrid rrf a=.5", {}), ("turn dense", {"bm25": False}), ("turn bm25", {"dense": False}),
                      ("turn wsum a=.5", {"fusion": "wsum"}), ("turn wsum a=.7", {"fusion": "wsum", "alpha": .7}), ("turn rrf a=.7", {"alpha": .7}), ("turn rrf a=.3", {"alpha": .3}),
                      ("turn speaker filter OFF", {"speaker_mode": "off"}), ("turn speaker filter SOFT", {"speaker_mode": "soft"}),
                      ("turn stem=none", {"stem_mode": "none"}), ("turn stem=trunc5", {"stem_mode": "trunc5"}), ("turn stem=snow5", {"stem_mode": "snow5"})]:
        row(label, evaluate(corpus, qs, ch, emb, tag=tag("turn"), **kw))
    for m in ["utt", "win2", "win3", "win4", "win5", "win3s3", "win6s3", "win8s4"]:
        ch = chunk_corpus(corpus, m)
        row(f"{m} hybrid ({len(ch)} chunks)", evaluate(corpus, qs, ch, emb, tag=tag(m)))
    ch = chunk_corpus(corpus, "win4")
    row("win4 GLOBAL scope (all q over whole history)", evaluate(corpus, qs, ch, emb, tag=tag("win4"), scope_all=True))
    ch = chunk_corpus(corpus, "turn")
    row("turn GLOBAL scope", evaluate(corpus, qs, ch, emb, tag=tag("turn"), scope_all=True))
    row("turn GLOBAL scope, speaker filter off", evaluate(corpus, qs, ch, emb, tag=tag("turn"), scope_all=True, speaker_mode="off"))
    emb.close()


if __name__ == "__main__":
    main()
