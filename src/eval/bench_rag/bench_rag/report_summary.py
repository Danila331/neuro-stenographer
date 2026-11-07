"""Tables from results/summary_*.json:  python -m bench_rag.report_summary results/summary_1.7b_final.json ..."""
import json
import sys

import numpy as np

from .sumdata import all_docs
from .text import terms


def copy_stats(rows, docs):
    cr, wl, n = 0, [], 0
    for r in rows:
        d = docs.get(r["doc"])
        if not d:
            continue
        for t in r["json"]["topics"]:
            for p in t["points"]:
                src = " ".join(d.rec.utts[x["n"] - 1].text for x in p["refs"])
                a, b = set(terms(p["text"])), set(terms(src))
                n += 1
                cr += bool(a) and len(a & b) / len(a | b) >= 0.8
                wl.append(len(p["text"].split()))
    return (cr / n if n else 0.0), (float(np.mean(wl)) if wl else 0.0)


def long_row(f, docs):
    d = json.load(open(f))
    r = next((x for x in d["rows"] if x["doc"] == "L16"), None)
    if not r:
        return
    audio = docs["L16"].rec.duration_ms / 1000
    ph = r["pp_s"] * 2.4 + r["tg_s"] * 3.8
    ph2 = r["pp_s"] * (1 + r["cached_tok"] / max(1, r["prompt_tok"])) * 2.4 + r["tg_s"] * 3.8
    print(f"| {f.split('/')[-1][8:-5]} | {audio / 60:.1f} мин / {len(docs['L16'].rec.utts)} реплик | {r['frags']} | {r['topics_gen']} (эталон 16) | {r['cov_range']:.2f} / {r['cov_kw']:.2f} | {r.get('facts_recall') or 0:.2f} | {r['gen_tok']} | {r['wall_s']:.0f} | {ph:.0f} ({ph / audio:.2f}×) | {ph2:.0f} |")


def main(files):
    docs = {d.id: d for d in all_docs("asr", long=True)}
    if files and files[0] == "--long":
        print("| модель | запись | фрагментов | тем | покрытие: диапазон / ключ. | решения и задачи | токенов сгенерировано | с ПК | с телефона (оценка) | без кэша префикса |")
        for f in files[1:]:
            long_row(f, docs)
        return
    print("| run | docs | topic cov (range) | topic cov (keywords) | grounded | speaker attribution | decisions/tasks recall | assignee ok | per-speaker claims recall (d1-d3) | misattributed | refs valid before filter | items dropped | verbatim share of points | words/point | s/doc PC | s/doc phone (est.) | + no prefix cache | peak RSS MB |")
    for f in files:
        d = json.load(open(f))
        rows = [r for r in d["rows"] if r["doc"] != "L16"]
        g = lambda k: [r[k] for r in rows if r.get(k) is not None]
        m = lambda k: f"{np.mean(g(k)):.2f}" if g(k) else "-"
        items, drop, refs, bad = (sum(r.get(k, 0) for r in rows) for k in ("items", "dropped", "refs", "ref_bad"))
        cp, wl = copy_stats(rows, docs)
        peak = d.get("peak", {}).get("rss_mb", "-")
        pp, tg, pt, ct = (sum(r.get(k, 0) for r in rows) for k in ("pp_s", "tg_s", "prompt_tok", "cached_tok"))
        ph = (pp * 2.4 + tg * 3.8) / len(rows)                       # M3 -> Galaxy S23 Ultra factors from bench_llm README (prefill x2.4, decode x3.8)
        ph2 = (pp * (1 + ct / max(1, pt)) * 2.4 + tg * 3.8) / len(rows)
        print(f"| {f.split('/')[-1][8:-5]} | {len(rows)} | {m('cov_range')} | {m('cov_kw')} | {m('grounded')} | {m('attrib')} | {m('facts_recall')} | {m('who_ok')} | {m('claims_recall')} | {m('claims_misattr')} | {1 - bad / max(1, refs):.2f} | {drop / max(1, items):.2f} | {cp:.2f} | {wl:.1f} | {m('wall_s')} | {ph:.0f} | {ph2:.0f} | {peak} |")


if __name__ == "__main__":
    main(sys.argv[1:])
