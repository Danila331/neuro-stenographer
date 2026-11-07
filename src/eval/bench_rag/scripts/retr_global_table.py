import sys
from bench_rag.chunker import chunk_corpus
from bench_rag.embed import make
from bench_rag.evalset import build_questions
from bench_rag.run_retrieval import evaluate
corpus_kind=sys.argv[1]
corpus, qs = build_questions(corpus_kind)
for m in sys.argv[2:]:
    emb=make(m)
    for ch in ["turn","win3"]:
        chunks=chunk_corpus(corpus,ch)
        for scope in (False, True):
            r=evaluate(corpus,qs,chunks,emb,tag=f"{corpus_kind}{ch}{emb.path.name}",scope_all=scope)
            a=r['all']; g=lambda k:r.get(k,{}).get('hit5','-')
            print(f"| {m} | {ch} | {'global' if scope else 'record/history'} | {a['hit1']} | {a['hit5']} | {a['mrr']} | {g('speaker')} | {g('topic')} | {g('history')} |",flush=True)
    emb.close()
