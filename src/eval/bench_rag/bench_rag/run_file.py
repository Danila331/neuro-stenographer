"""Python-эталон выжимки на одном файле расшифровки (`{"transcript":[...]}`), для сравнения с Kotlin (`jvm-smoke --summary`):
python -m bench_rag.run_file <transcript.json> <0.6b|1.7b|4b> [out.json] [--overview]   (лексические векторы, k = 4 -- как без эмбеддера)."""
import json
import sys
import time
from pathlib import Path

from . import summary as S
from .data import Record, Utt
from .llm import server
from .segment import lexical_vectors


def main():
    path, model = sys.argv[1], sys.argv[2]
    out = next((a for a in sys.argv[3:] if a.endswith(".json")), None)
    tr = json.loads(Path(path).read_text(encoding="utf-8"))["transcript"]
    rec = Record(Path(path).stem, [Utt(i, u["speaker_id"], u["start_ms"], u["end_ms"], u["text"]) for i, u in enumerate(tr)])
    vecs = lexical_vectors([u.text for u in rec.utts])
    with server(model, n_ctx=4096) as srv:
        t0 = time.perf_counter()
        r = S.summarize(srv, rec, vecs, overview="--overview" in sys.argv, seg_kw={"k": 4, "alpha": 0.0})
        wall = time.perf_counter() - t0
    j = S.to_json(rec, r["topics"], r["overview"])
    print(f"llm: {len(r['topics'])} тем (до склейки {r['raw_topics']}), фрагментов {r['fragments']}, {wall * 1000:.0f} мс, {r['stats']}")
    print("pp_s=%.1f tg_s=%.1f gen_tok=%d" % (sum(c["prompt_ms"] for c in r["calls"]) / 1000, sum(c["predicted_ms"] for c in r["calls"]) / 1000, sum(c["predicted_n"] for c in r["calls"])))
    for t in j["topics"]:
        print(f"# {t['title']} [{t['start_ms'] // 1000}-{t['end_ms'] // 1000} с]")
        for p in t["points"]:
            print(f"   {p['speaker']}: {p['text']} {[x['n'] for x in p['refs']]}")
        for p in t["decisions"]:
            print(f"   РЕШЕНИЕ: {p['text']}")
        for p in t["tasks"]:
            print(f"   ЗАДАЧА: {p['who']} {p['text']}")
    if out:
        Path(out).write_text(json.dumps({"wall_ms": wall * 1000, "stats": vars(r["stats"]), "doc": j, "plan_fragments": r["fragments"]}, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
