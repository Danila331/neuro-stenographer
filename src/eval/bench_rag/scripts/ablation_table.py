"""Component ablation on a document subset: python scripts/ablation_table.py results/summary_1.7b_fin2.json results/summary_1.7b_nogram.json ..."""
import json
import sys

import numpy as np

SUB = {"M1", "M2", "M3", "d1_budget", "d2_bridge", "d3_holiday"}
print("| вариант (1.7B, 6 документов) | покрытие тем: диапазон / ключ. | атрибуция | «заземлено» | решения и задачи | тезисы говорящих | ссылки мимо до фильтра | отброшено | фрагментов | токенов сгенерировано |")
print("|---|---|---|---|---|---|---|---|---|---|")
for f in sys.argv[1:]:
    rows = [r for r in json.load(open(f))["rows"] if r["doc"] in SUB]
    m = lambda k: np.mean([r[k] for r in rows if r.get(k) is not None])
    refs, bad, items, drop = (sum(r[k] for r in rows) for k in ("refs", "ref_bad", "items", "dropped"))
    name = f.split("summary_1.7b_")[-1][:-5]
    print(f"| {name} | {m('cov_range'):.2f} / {m('cov_kw'):.2f} | {m('attrib'):.2f} | {m('grounded'):.2f} | {m('facts_recall'):.2f} | {m('claims_recall'):.2f} | {bad / max(1, refs):.0%} | {drop / max(1, items):.0%} | {sum(r['frags'] for r in rows)} | {sum(r['gen_tok'] for r in rows)} |")
