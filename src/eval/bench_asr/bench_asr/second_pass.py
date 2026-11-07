"""Потенциал второго прохода / ROVER по готовым гипотезам (results/raw/<tag>/acc__*.jsonl).

python -m bench_asr.second_pass --tag fleurs --primary gigaam3_rnnt --secondary whisper_turbo_int8 [--others a,b] \
   --rtf-primary 0.06 --rtf-secondary 0.5 --out results/second_pass_fleurs.md
"""
from __future__ import annotations
import argparse, json
from collections import Counter
from pathlib import Path
import numpy as np
from .textnorm import tokens as _tok, edit_counts, wer_counts


def norm(s: str) -> str:
    """norm_num: нормализация + числа словами (Whisper/e2e пишут цифрами)."""
    return " ".join(_tok(s, "norm_num"))
from .words import align_pairs

HERE = Path(__file__).resolve().parents[1]


def load(tag, key):
    return [json.loads(l) for l in open(HERE / "results" / "raw" / tag / f"acc__{key}__t4.jsonl", encoding="utf-8")]


def align_sets(*sets):
    """Оставить только общие файлы (по id) в одном порядке."""
    ids = None
    for s in sets:
        k = {r["id"] for r in s}
        ids = k if ids is None else ids & k
    return [[r for r in s if r["id"] in ids] for s in sets]


def errs(ref, hyp):
    s, d, i = edit_counts(norm(ref).split(), norm(hyp).split())
    return s + d + i


def rover(hyps: list[str], weights=None) -> str:
    """Простой ROVER: пивот = первая гипотеза; остальные выравниваются к ней; голосование по слотам
    (слово/пропуск); вставки берём, если их предлагают >= 2 систем с одним и тем же словом."""
    ws = [norm(h).split() for h in hyps]
    weights = weights or [1.0] * len(ws)
    piv = ws[0]
    slots = [[Counter()] for _ in range(1)]
    cand = [Counter() for _ in piv]           # голоса за слово в слоте i (None = пропуск)
    ins = [Counter() for _ in range(len(piv) + 1)]  # вставки перед слотом i
    for k, w in enumerate(ws):
        pairs = align_pairs(piv, w)
        pos = 0
        for i, j in pairs:
            if i is not None:
                cand[i][w[j] if j is not None else None] += weights[k]; pos = i + 1
            else:
                ins[pos][w[j]] += weights[k]
    out = []
    for i in range(len(piv) + 1):
        if ins[i]:
            wd, c = ins[i].most_common(1)[0]
            if c > sum(weights) / 2:
                out.append(wd)
        if i < len(piv):
            wd, c = max(cand[i].items(), key=lambda kv: (kv[1], kv[0] == piv[i]))
            if wd is not None:
                out.append(wd)
    return " ".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True); ap.add_argument("--primary", required=True); ap.add_argument("--secondary", required=True)
    ap.add_argument("--others", default=""); ap.add_argument("--n", type=int, default=None)
    ap.add_argument("--rtf-primary", type=float, default=0.06); ap.add_argument("--rtf-secondary", type=float, default=0.5)
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    P, S = align_sets(load(a.tag, a.primary), load(a.tag, a.secondary))
    n = min(len(P), len(S), a.n or 10 ** 9)
    P, S = P[:n], S[:n]
    N = sum(len(norm(r["ref"]).split()) for r in P)
    eP = np.array([errs(r["ref"], r["hyp"]) for r in P]); eS = np.array([errs(r["ref"], r["hyp"]) for r in S])
    dur = np.array([r["dur_s"] for r in P])
    lines = [f"# Второй проход: {a.primary} -> {a.secondary} ({a.tag}, {n} файлов, {int(dur.sum())} с, {N} слов)\n",
             f"- WER primary = {100*eP.sum()/N:.2f}%, secondary = {100*eS.sum()/N:.2f}%",
             f"- Оракул (лучший из двух на каждой фразе) = {100*np.minimum(eP,eS).sum()/N:.2f}%",
             f"- Фраз, где secondary строго лучше: {int((eS<eP).sum())}/{n}; хуже: {int((eS>eP).sum())}/{n}; поровну: {int((eS==eP).sum())}/{n}\n"]
    feats = {}
    for k in ("mean_lp", "min_lp"):
        if P[0].get(k) is not None:
            feats[k] = np.array([r[k] for r in P])
    feats["wps_dev"] = -np.abs(np.array([len(norm(r["hyp"]).split()) / r["dur_s"] for r in P]) - 2.3)  # слов/с далеко от нормы
    feats["len_tok_ratio"] = None
    feats.pop("len_tok_ratio")
    lines.append("## Re-decode доли f наименее уверенных фраз (по признаку primary)\n")
    lines.append("| признак | f | WER | доля аудио на secondary | эфф. RTF (последовательно) |\n|---|---|---|---|---|")
    for name, v in feats.items():
        order = np.argsort(v)  # самые неуверенные первыми
        for f in (0.1, 0.2, 0.3, 0.5, 1.0):
            k = int(round(f * n)); idx = set(order[:k].tolist())
            e = sum(eS[i] if i in idx else eP[i] for i in range(n))
            frac_audio = dur[list(idx)].sum() / dur.sum() if idx else 0
            lines.append(f"| {name} | {f:.1f} | {100*e/N:.2f}% | {100*frac_audio:.0f}% | {a.rtf_primary + frac_audio*a.rtf_secondary:.3f} |")
        # «идеальный» выбор k худших по ошибкам primary (верхняя граница для данного f)
    lines.append("\n## Оракульные пороги (верхняя граница при идеальном выборе фраз)\n")
    lines.append("| f | WER | RTF |\n|---|---|---|")
    gain = eP - eS
    order = np.argsort(-gain)
    for f in (0.1, 0.2, 0.3):
        k = int(round(f * n)); idx = order[:k]
        e = sum(eS[i] if (i in set(idx.tolist()) and gain[i] > 0) else eP[i] for i in range(n))
        lines.append(f"| {f:.1f} | {100*e/N:.2f}% | {a.rtf_primary + dur[idx].sum()/dur.sum()*a.rtf_secondary:.3f} |")
    if a.others:
        names = [a.primary, a.secondary] + a.others.split(",")
        H = align_sets(P, S, *[load(a.tag, k) for k in a.others.split(",")])
        lines.append("\n## ROVER (голосование по словам)\n\n| системы | WER |\n|---|---|")
        for sub in [names[:2] + names[2:3]] if len(names) >= 3 else []:
            pass
        import itertools
        for r_ in range(3, len(names) + 1):
            for combo in itertools.combinations(range(len(names)), r_):
                hy = [[H[c][i]["hyp"] for c in combo] for i in range(n)]
                e = sum(errs(P[i]["ref"], rover(hy[i])) for i in range(n))
                lines.append(f"| {' + '.join(names[c] for c in combo)} | {100*e/N:.2f}% |")
    txt = "\n".join(lines)
    print(txt)
    if a.out:
        Path(a.out).write_text(txt + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
