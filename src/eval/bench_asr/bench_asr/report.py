"""Сводка results/raw/<tag>/*.summary.json -> results/matrix_<tag>.csv + .md

python -m bench_asr.report --tag fleurs [--subset 40]
WER на общем подмножестве (первые --subset файлов) пересчитывается из jsonl — чтобы whisper (медленный) сравнивался с остальными.
"""
from __future__ import annotations
import argparse, json, csv
from pathlib import Path
from .textnorm import wer_counts

HERE = Path(__file__).resolve().parents[1]


def wer_of(rows, mode):
    S = D = I = N = 0
    for r in rows:
        s, d, i, n = wer_counts(r["ref"], r["hyp"], mode); S += s; D += d; I += i; N += n
    return (S + D + I) / max(N, 1)


def read_jsonl(p):
    return [json.loads(l) for l in open(p, encoding="utf-8")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True); ap.add_argument("--subset", type=int, default=40)
    a = ap.parse_args()
    raw = HERE / "results" / "raw" / a.tag
    models = sorted({p.name.split("__")[1] for p in raw.glob("*.summary.json")})
    table = []
    for m in models:
        row = {"model": m}
        acc = raw / f"acc__{m}__t4.jsonl"
        if acc.exists():
            s = json.load(open(str(acc) + ".summary.json")); rows = read_jsonl(acc)
            sub = rows[: a.subset]
            row.update(kind=s["kind"], precision=s["precision"], size_mb=round(s["size_mb"]), n=s["n_items"],
                       wer_raw=round(100 * wer_of(rows, "raw"), 2), wer_norm=round(100 * wer_of(rows, "norm"), 2),
                       wer_norm_num=round(100 * wer_of(rows, "norm_num"), 2),
                       **{f"wer_raw_s{a.subset}": round(100 * wer_of(sub, "raw"), 2),
                          f"wer_norm_s{a.subset}": round(100 * wer_of(sub, "norm"), 2)},
                       peak_rss_mb=round(s["peak_rss_mb"]), load_s=round(s["load_s"], 2), rtf_acc_t4=round(s["rtf"], 3),
                       load_avg=round(s["load_avg"], 1))
        for t in (1, 4):
            p = raw / f"speed__{m}__t{t}.jsonl.summary.json"
            if p.exists():
                s = json.load(open(p))
                row[f"rtf_t{t}"] = round(s["rtf"], 3); row[f"cpu_rtf_t{t}"] = round(s.get("cpu_rtf", 0), 3); row[f"rtf_t{t}_n"] = s["n_items"]; row[f"load_avg_t{t}"] = round(s["load_avg"], 1)
        table.append(row)
    keys = []
    for r in table:
        for k in r:
            if k not in keys: keys.append(k)
    out = HERE / "results" / f"matrix_{a.tag}.csv"
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=keys); w.writeheader(); w.writerows(table)
    md = ["| " + " | ".join(keys) + " |", "|" + "---|" * len(keys)]
    for r in table:
        md.append("| " + " | ".join(str(r.get(k, "")) for k in keys) + " |")
    (HERE / "results" / f"matrix_{a.tag}.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print("\n".join(md))
    # WER по условиям (condition из manifest) для acc-прогонов
    conds = {}
    for m in models:
        acc = raw / f"acc__{m}__t4.jsonl"
        if not acc.exists():
            continue
        by = {}
        for r in read_jsonl(acc):
            by.setdefault(r.get("condition") or "all", []).append(r)
        for c, rows in by.items():
            conds.setdefault(c, {})[m] = (100 * wer_of(rows, "norm"), len(rows))
    if len(conds) > 1:
        cl = sorted(conds)
        md2 = ["| model | " + " | ".join(f"{c} (n={max(v[1] for v in conds[c].values())})" for c in cl) + " |", "|" + "---|" * (len(cl) + 1)]
        for m in models:
            if any(m in conds[c] for c in cl):
                md2.append(f"| {m} | " + " | ".join(f"{conds[c][m][0]:.2f}" if m in conds[c] else "" for c in cl) + " |")
        (HERE / "results" / f"bycond_{a.tag}.md").write_text("\n".join(md2) + "\n", encoding="utf-8")
        print("\n".join(md2))


if __name__ == "__main__":
    main()
