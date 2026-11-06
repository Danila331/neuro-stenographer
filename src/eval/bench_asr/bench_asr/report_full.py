"""Сводка WER с разрезами: python -m bench_asr.report_full --tag full_rudev --by source,condition [--split dev|test|all]

Читает results/raw/<tag>/acc__<model><suffix>__t4.jsonl (в строках есть condition/source/scenario/split).
WER считается суммой ошибок / суммой слов в группе (micro-average). Столбцы: norm (основной), raw, norm_num по --metric.
"""
from __future__ import annotations
import argparse, json, collections
from pathlib import Path
from .textnorm import wer_counts

HERE = Path(__file__).resolve().parents[1]


def micro(rows, mode):
    S = D = I = N = 0
    for r in rows:
        s, d, i, n = wer_counts(r["ref"], r["hyp"], mode); S += s; D += d; I += i; N += n
    return 100 * (S + D + I) / max(N, 1), N


def has_punct(ref: str) -> bool:
    import re
    return bool(ref[:1].isupper() or re.search(r"[,.!?;:]", ref))


def variant(r):
    i = r.get("meta_id") or r["id"]
    for v in ("noise", "phone", "reverb", "farfield"):
        if f"__{v}" in i:
            return v
    return "orig"


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True); ap.add_argument("--by", default="")
    ap.add_argument("--split", default="all"); ap.add_argument("--metric", default="norm")
    ap.add_argument("--suffix", default=""); ap.add_argument("--models", default="")
    ap.add_argument("--refstyle", default="all", help="all|punct — только файлы, где эталон со знаками и регистром")
    ap.add_argument("--out", default=None)
    a = ap.parse_args(argv)
    d = HERE / "results" / "raw" / a.tag
    files = sorted(d.glob(f"acc__*{a.suffix}__t4.jsonl"))
    data = {}
    for f in files:
        m = f.name[len("acc__"):-len(f"{a.suffix}__t4.jsonl")]
        if a.models and m not in a.models.split(","):
            continue
        rows = [json.loads(l) for l in open(f, encoding="utf-8")]
        if a.refstyle == "punct":
            rows = [r for r in rows if has_punct(r["ref"])]
        if a.split != "all":
            rows = [r for r in rows if r.get("split") == a.split]
        data[m] = rows
    keys = [k for k in a.by.split(",") if k]
    groups = ["ALL"]
    colfn = {}
    for k in keys:
        vals = sorted({(variant(r) if k == "variant" else str(r.get(k))) for rows in data.values() for r in rows})
        for v in vals:
            groups.append(f"{k}={v}"); colfn[f"{k}={v}"] = (k, v)
    head = ["model"] + [g + (f" (n={len(next(iter(data.values())))})" if g == "ALL" and data else "") for g in groups]
    lines = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    for m, rows in data.items():
        cells = []
        for g in groups:
            if g == "ALL":
                sub = rows
            else:
                k, v = colfn[g]
                sub = [r for r in rows if (variant(r) if k == "variant" else str(r.get(k))) == v]
            cells.append(f"{micro(sub, a.metric)[0]:.2f}" if sub else "")
        lines.append(f"| {m} | " + " | ".join(cells) + " |")
    ns = ["| n слов | " + " | ".join(str(micro([r for r in next(iter(data.values()))] if g == "ALL" else
          [r for r in next(iter(data.values())) if (variant(r) if colfn[g][0] == "variant" else str(r.get(colfn[g][0]))) == colfn[g][1]], a.metric)[1]) for g in groups) + " |"] if data else []
    txt = "\n".join(lines + ns)
    print(f"# {a.tag} split={a.split} metric={a.metric}\n" + txt)
    if a.out:
        Path(a.out).write_text(f"# {a.tag} split={a.split} metric={a.metric}\n\n" + txt + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
