"""Grid sweeps over sherpa-onnx diarization configs.  Writes one CSV row per (cfg, condition).

python -m bench_diar.sweep --dataset <root> --split dev --embs a,b --thresholds 0.3,0.4,0.5 --out results/x.csv
"""
from __future__ import annotations

import argparse
import csv
import itertools
from dataclasses import asdict
from pathlib import Path

from .data import load_dataset
from .metrics import aggregate, score
from .runner import Cfg, run_items

FIELDS = ["seg", "seg_int8", "emb", "threshold", "num_speakers", "min_on", "min_off", "shift",
          "condition", "n", "der0", "der25", "miss25", "fa25", "conf25", "jer", "spk_acc", "spk_mae", "rtf", "cpu_rtf", "threads"]


def eval_cfg(cfg: Cfg, items, procs: int):
    runs = run_items(cfg, items, procs)
    rows = []
    groups = {"all": list(range(len(items)))}
    for i, it in enumerate(items):
        groups.setdefault(it.condition, []).append(i)
    for cond, idx in groups.items():
        a0 = aggregate([score(items[i].ref, runs[i]["segs"], items[i].duration, 0.0) for i in idx])
        a25 = aggregate([score(items[i].ref, runs[i]["segs"], items[i].duration, 0.25) for i in idx])
        rtf = sum(runs[i]["sec"] for i in idx) / sum(runs[i]["dur"] for i in idx)
        cpu_rtf = sum(runs[i].get("cpu", 0) for i in idx) / sum(runs[i]["dur"] for i in idx)
        rows.append({**asdict(cfg), "condition": cond, "n": a0["n"], "der0": a0["der"], "der25": a25["der"],
                     "miss25": a25["miss"], "fa25": a25["fa"], "conf25": a25["conf"], "jer": a0["jer"],
                     "spk_acc": a0["spk_acc"], "spk_mae": a0["spk_mae"], "rtf": rtf, "cpu_rtf": cpu_rtf})
    return rows


def write_rows(path: Path, rows: list[dict]):
    new = not path.exists()
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "a", newline="") as f:
        w = csv.DictWriter(f, FIELDS, extrasaction="ignore")
        if new:
            w.writeheader()
        for r in rows:
            w.writerow({k: (round(v, 4) if isinstance(v, float) else v) for k, v in r.items()})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", required=True)
    ap.add_argument("--split", default="dev")
    ap.add_argument("--segs", default="pyannote3")
    ap.add_argument("--int8", default="1")
    ap.add_argument("--embs", required=True)
    ap.add_argument("--thresholds", default="0.5")
    ap.add_argument("--num-speakers", default="-1")
    ap.add_argument("--min-on", default="0.3")
    ap.add_argument("--min-off", default="0.5")
    ap.add_argument("--shift", default="0.1")
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--procs", type=int, default=4)
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=None)
    a = ap.parse_args()
    items = load_dataset(a.dataset, split=a.split, limit=a.limit)
    f = lambda s, t: [t(x) for x in s.split(",")]
    for seg, i8, emb, th, ns, mon, mof, sh in itertools.product(
            f(a.segs, str), f(a.int8, lambda x: x == "1"), f(a.embs, str), f(a.thresholds, float),
            f(a.num_speakers, int), f(a.min_on, float), f(a.min_off, float), f(a.shift, float)):
        cfg = Cfg(seg=seg, seg_int8=i8, emb=emb, threshold=th, num_speakers=ns, min_on=mon, min_off=mof,
                  shift=sh, threads=a.threads)
        rows = eval_cfg(cfg, items, a.procs)
        for r in rows:
            r["threads"] = a.threads
        write_rows(Path(a.out), rows)
        r = rows[0]
        print(f"{seg}/{i8} {emb[-34:]:34s} th={th} ns={ns} on={mon} off={mof} sh={sh}: DER0={r['der0']:.3f} "
              f"DER.25={r['der25']:.3f} JER={r['jer']:.3f} spk_acc={r['spk_acc']:.2f} rtf={r['rtf']:.3f}", flush=True)


if __name__ == "__main__":
    main()
