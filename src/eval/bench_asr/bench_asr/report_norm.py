"""Сводка по нормализации громкости: results/raw/norm_rudev (att = ослабление в дБ, none/peak/rms).
python -m bench_asr.report_norm [--metric norm_num] --out results/final/normalize_gain.md"""
import argparse, json, os
from pathlib import Path
from .report_full import micro

HERE = Path(__file__).resolve().parents[1]
CONDS = ["clean", "farfield", "noise", "phone", "reverb"]


def load(p):
    return [json.loads(l) for l in open(p, encoding="utf-8")]


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--metric", default="norm_num"); ap.add_argument("--out", default=None)
    ap.add_argument("--models", default="gigaam3_ctc,zip_ru_2504_int8")
    a = ap.parse_args()
    base = HERE / "results" / "raw"
    lines = [f"WER {a.metric}, % (ru-asr-dev, split=dev, 498 файлов); att = имитация тихой записи (ослабление в дБ + int16)\n",
             "| модель | att, дБ | норм. | ALL | " + " | ".join(CONDS) + " |", "|" + "---|" * (4 + len(CONDS))]
    for m in a.models.split(","):
        for att in (0, 30, 44):
            for n in ("none", "peak", "rms"):
                p = base / "norm_rudev" / f"acc__{m}__att{att}_{n}__t4.jsonl"
                if att == 0 and n == "none":
                    p = base / "full_rudev" / f"acc__{m}__t4.jsonl"
                if not p.exists():
                    continue
                r = [x for x in load(p) if x.get("split") == "dev"]
                cells = [f"{micro(r, a.metric)[0]:.2f}"] + [f"{micro([x for x in r if x['condition'] == c], a.metric)[0]:.2f}" for c in CONDS]
                lines.append(f"| {m} | {att} | {n} | " + " | ".join(cells) + " |")
    for att in (36, 44):
        for g in (30, 45):
            p = base / "norm_rudev" / f"acc__gigaam3_ctc__att{att}_peak_g{g}__t4.jsonl"
            if p.exists():
                r = [x for x in load(p) if x.get("split") == "dev"]
                cells = [f"{micro(r, a.metric)[0]:.2f}"] + [f"{micro([x for x in r if x['condition'] == c], a.metric)[0]:.2f}" for c in CONDS]
                lines.append(f"| gigaam3_ctc | {att} | peak, max_gain={g} | " + " | ".join(cells) + " |")
    txt = "\n".join(lines)
    print(txt)
    if a.out:
        Path(a.out).write_text(txt + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
