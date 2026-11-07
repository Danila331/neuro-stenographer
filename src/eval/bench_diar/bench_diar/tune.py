"""Threshold / duration sweeps on cached pipeline stages (cheap once stage B exists).

python -m bench_diar.tune --dataset DIR --embs a,b --shift 0.3 --split dev --out results/x.csv
Each row: emb, shift, threshold, min_on, min_off, mode(auto|oracle), condition -> metrics.
"""
from __future__ import annotations

import argparse
import csv
import itertools
from pathlib import Path

import numpy as np

from .data import load_audio, load_dataset
from .metrics import aggregate, score
from .pipeline import PCfg, Prepared, cluster_fast, assemble

COLS = ["emb", "seg", "shift", "split", "mode", "threshold", "min_on", "min_off", "condition", "n",
        "der0", "der25", "miss25", "fa25", "conf25", "jer", "spk_acc", "spk_mae"]


def prepare(items, cfg: PCfg):
    return [Prepared(it.id, load_audio(it.audio), cfg) for it in items]


def run_grid(items, preps, cfg, thresholds, min_ons, min_offs, modes=("auto", "oracle"), split=""):
    rows = []
    for mode in modes:
        for th in (thresholds if mode == "auto" else [0.5]):
            cls = []
            for it, P in zip(items, preps):
                k = -1 if mode == "auto" else it.num_speakers
                cls.append(cluster_fast(P.embs, th, k))
            for mon, mof in itertools.product(min_ons, min_offs):
                hyps = [assemble(P.labels, P.sh, P.sm, len(P.audio), P.pairs, cl, mon, mof)
                        for P, cl in zip(preps, cls)]
                groups = {"all": list(range(len(items)))}
                for i, it in enumerate(items):
                    groups.setdefault(it.condition, []).append(i)
                for cond, idx in groups.items():
                    s0 = [score(items[i].ref, hyps[i], items[i].duration, 0.0) for i in idx]
                    s25 = [score(items[i].ref, hyps[i], items[i].duration, 0.25) for i in idx]
                    a0, a25 = aggregate(s0), aggregate(s25)
                    rows.append(dict(emb=cfg.emb, seg=cfg.seg, shift=cfg.shift, split=split, mode=mode,
                                     threshold=th if mode == "auto" else "", min_on=mon, min_off=mof,
                                     condition=cond, n=a0["n"], der0=a0["der"], der25=a25["der"],
                                     miss25=a25["miss"], fa25=a25["fa"], conf25=a25["conf"], jer=a0["jer"],
                                     spk_acc=a0["spk_acc"], spk_mae=a0["spk_mae"]))
    return rows


def write(path: Path, rows):
    new = not path.exists()
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "a", newline="") as f:
        w = csv.DictWriter(f, COLS)
        if new:
            w.writeheader()
        for r in rows:
            w.writerow({k: (round(v, 4) if isinstance(v, float) else v) for k, v in r.items()})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", required=True)
    ap.add_argument("--split", default="dev")
    ap.add_argument("--seg", default="pyannote3")
    ap.add_argument("--embs", required=True)
    ap.add_argument("--shift", type=float, default=0.3)
    ap.add_argument("--exclude-overlap", default="1")
    ap.add_argument("--thresholds", default=",".join(f"{x:.2f}" for x in np.arange(0.2, 1.21, 0.05)))
    ap.add_argument("--min-on", default="0.3")
    ap.add_argument("--min-off", default="0.5")
    ap.add_argument("--modes", default="auto,oracle")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    items = load_dataset(a.dataset, split=a.split)
    fl = lambda s: [float(x) for x in s.split(",")]
    for e in a.embs.split(","):
        cfg = PCfg(seg=a.seg, emb=e, shift=a.shift, exclude_overlap=a.exclude_overlap == "1")
        preps = prepare(items, cfg)
        rows = run_grid(items, preps, cfg, fl(a.thresholds), fl(a.min_on), fl(a.min_off), tuple(a.modes.split(",")), a.split)
        write(Path(a.out), rows)
        best = min((r for r in rows if r["mode"] == "auto" and r["condition"] == "all"), key=lambda r: r["der25"])
        orc = [r for r in rows if r["mode"] == "oracle" and r["condition"] == "all"]
        print(f"{e}: best auto th={best['threshold']} DER.25={best['der25']:.3f} DER0={best['der0']:.3f} "
              f"spk_acc={best['spk_acc']:.2f}" + (f" | oracle DER.25={orc[0]['der25']:.3f}" if orc else ""), flush=True)


if __name__ == "__main__":
    main()
