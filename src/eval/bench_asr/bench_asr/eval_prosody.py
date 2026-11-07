"""Подбор порогов просодической пунктуации на dev и проверка на test.
python -m bench_asr.eval_prosody --base gigaam3_ctc --sets full_rudev,full_cats,full_synth --out results/final/prosody.md
Использует токены/метки из results/raw/<set>/acc__<base>__t4.jsonl. Метрики: pc_sep_num (знаки отдельными токенами, цифры словами) и pc_num.
"""
from __future__ import annotations
import argparse, json, itertools
from pathlib import Path
from .prosody import punctuate
from .report_full import has_punct
from .textnorm import wer_counts

HERE = Path(__file__).resolve().parents[1]


def load(tag, m):
    p = HERE / "results" / "raw" / tag / f"acc__{m}__t4.jsonl"
    return [json.loads(l) for l in open(p, encoding="utf-8")] if p.exists() else []


def score(rows, hyps, mode):
    S = D = I = N = 0
    for r, h in zip(rows, hyps):
        s, d, i, n = wer_counts(r["ref"], h, mode); S += s; D += d; I += i; N += n
    return 100 * (S + D + I) / max(N, 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="gigaam3_ctc"); ap.add_argument("--sets", default="full_rudev,full_cats,full_synth")
    ap.add_argument("--compare", default="gigaam3_ctc_punct,gigaam3_rnnt_punct"); ap.add_argument("--out", default=None)
    a = ap.parse_args()
    lines = []
    commas = [0.15, 0.2, 0.25, 0.3, 0.4, 0.5, 9]; periods = [0.4, 0.5, 0.6, 0.8, 1.0, 1.5, 9]
    for tag in a.sets.split(","):
        base = [r for r in load(tag, a.base) if has_punct(r["ref"]) and r["tokens"]]
        if not base:
            continue
        dev = [r for r in base if r["split"] == "dev"]; test = [r for r in base if r["split"] == "test"]
        best = None
        for tc, tp in itertools.product(commas, periods):
            if tp <= tc:
                continue
            hy = [punctuate(r["tokens"], r["timestamps"], tc, tp) for r in dev]
            w = score(dev, hy, "pc_sep_num")
            if best is None or w < best[0]:
                best = (w, tc, tp)
        _, tc, tp = best
        lines.append(f"\n### {tag}: пороги по dev: t_comma={tc}, t_period={tp} (файлов со знаками в эталоне: dev {len(dev)}, test {len(test)})\n")
        lines.append("| система | split | WER pc_sep_num | WER pc_num | WER norm_num |\n|---|---|---|---|---|")
        def row(name, rows_, hyps_, sp):
            lines.append(f"| {name} | {sp} | {score(rows_, hyps_, 'pc_sep_num'):.2f} | {score(rows_, hyps_, 'pc_num'):.2f} | {score(rows_, hyps_, 'norm_num'):.2f} |")
        for sp, rows_ in (("dev", dev), ("test", test)):
            if not rows_:
                continue
            row(f"{a.base} (без знаков)", rows_, [r["hyp"] for r in rows_], sp)
            row(f"{a.base} + просодия", rows_, [punctuate(r["tokens"], r["timestamps"], tc, tp) for r in rows_], sp)
            row(f"{a.base} + просодия (дефолт 0.25/0.6)", rows_, [punctuate(r["tokens"], r["timestamps"]) for r in rows_], sp)
            for cm in a.compare.split(","):
                cr = {r["id"]: r for r in load(tag, cm)}
                ids = [r for r in rows_ if r["id"] in cr]
                if ids:
                    row(cm, ids, [cr[r["id"]]["hyp"] for r in ids], sp)
    txt = "\n".join(lines)
    print(txt)
    if a.out:
        Path(a.out).write_text(txt + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
