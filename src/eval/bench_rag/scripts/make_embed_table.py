"""Embedding model comparison table (markdown) from results/*.json + embed_speed.jsonl + retr_final_*.md."""
import json
import re
from pathlib import Path

R = Path(__file__).resolve().parent.parent / "results"
LIC = {"rubert-tiny2": ("MIT", "27", 312), "rubert-tiny-turbo": ("MIT", "27", 312), "user2-small": ("Apache-2.0", "33", 384), "e5-small": ("MIT", "112", 384),
       "labse-ru-turbo": ("MIT", "122", 768), "user-base": ("Apache-2.0", "119*", 768), "e5-base": ("MIT", "265", 768), "qwen3-emb-0.6b": ("Apache-2.0", "609 (Q8_0)", 1024)}


def load_scoped():
    d = {}
    for f in ("retr_scoped.json", "retr_qwen3.json"):
        for r in json.load(open(R / f)):
            if r.get("mode") == "hybrid":
                m = r["model"].replace("-torch", "")
                d[(m, r["corpus"], r["chunk"], "scoped")] = (r["all"]["hit5"], r["all"]["mrr"])
    return d


def load_global():
    d = {}
    for f in ("retr_final_asr.md", "retr_final_ref.md"):
        p = R / f
        if not p.exists():
            continue
        corpus = "asr" if "asr" in f else "ref"
        for ln in p.read_text().splitlines():
            c = [x.strip() for x in ln.strip("|").split("|")]
            if len(c) >= 9 and c[0] != "model" and re.match(r"\d", c[4]):
                d[(c[0].replace("-torch", ""), corpus, c[1], "global" if c[2] == "global" else "scoped")] = (float(c[4]), float(c[5]))
    return d


def main():
    gl = load_global()
    sc = gl
    sp = {}
    for ln in open(R / "embed_speed.jsonl"):
        r = json.loads(ln)
        sp[(r["model"], r["threads"])] = r
    g = lambda d, k: ("%.2f / %.2f" % d[k]) if k in d else "-"
    print("| Модель | Лиц. | int8, МБ | dim | реплика мс (4 пот. / 1 пот.) | окно из 3 ходов мс (4 / 1) | RSS процесса, МБ | turn: hit@5 / MRR | win3: hit@5 / MRR | win3, весь корпус: hit@5 / MRR | ref turn: hit@5 / MRR |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for m, (lic, size, dim) in LIC.items():
        a, b = sp.get((m, 4)), sp.get((m, 1))
        if m == "qwen3-emb-0.6b":
            a, b = sp.get((m, 4)), sp.get((m, 1))
        print(f"| {m} | {lic} | {size} | {dim} | {a['utt_p50_ms']} / {b['utt_p50_ms']} | {a['chunk_p50_ms']} / {b['chunk_p50_ms']} | {a['peak_rss_mb']} | {g(sc, (m, 'asr', 'turn', 'scoped'))} | {g(sc, (m, 'asr', 'win3', 'scoped'))} | {g(gl, (m, 'asr', 'win3', 'global'))} | {g(sc, (m, 'ref', 'turn', 'scoped'))} |")


if __name__ == "__main__":
    main()
