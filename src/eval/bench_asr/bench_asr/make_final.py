"""Итоговая сводная таблица ru: python -m bench_asr.make_final > results/final/SUMMARY_ru.md"""
import json
from pathlib import Path
from .report_full import micro, has_punct

HERE = Path(__file__).resolve().parents[1]
MODELS = ["gigaam3_ctc", "gigaam3_rnnt", "gigaam3_ctc_punct", "gigaam3_rnnt_punct", "gigaam2_ctc", "zip_ru_2504_int8", "zip_ru_2504_fp32",
          "fc_multi_ctc", "fc_multi_rnnt", "parakeet_v3"]
SETS = [("ru-asr-dev", "full_rudev"), ("dialog-synth (реплики)", "full_synth"), ("dialog-cats (реплики)", "full_cats")]


def load(tag, m):
    p = HERE / "results" / "raw" / tag / f"acc__{m}__t4.jsonl"
    return [json.loads(l) for l in open(p, encoding="utf-8")] if p.exists() else None


def main():
    for metric, only_punct in (("norm_num", False), ("norm", False), ("pc_sep_num", True), ("pc_num", True)):
        print(f"\n### WER {metric}, % {'(только файлы с эталоном со знаками и регистром)' if only_punct else ''}\n")
        head = "| модель | " + " | ".join(f"{n} dev | {n} test" for n, _ in SETS) + " |"
        print(head); print("|" + "---|" * (1 + 2 * len(SETS)))
        for m in MODELS:
            cells = []
            for n, tag in SETS:
                rows = load(tag, m)
                for sp in ("dev", "test"):
                    if rows is None:
                        cells.append("")
                        continue
                    r = [x for x in rows if x["split"] == sp and (has_punct(x["ref"]) or not only_punct)]
                    cells.append(f"{micro(r, metric)[0]:.2f}" if r else "")
            if any(cells):
                print(f"| {m} | " + " | ".join(cells) + " |")


if __name__ == "__main__":
    main()
