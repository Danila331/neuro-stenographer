"""CLI: python -m bench_llm.run_diarfix --models qwen3-4b-q4_k_m"""
from __future__ import annotations

import argparse
import json
import time

from .data import load_dialogs, inject_boundary_errors
from .diarfix import fix_dialog, wer_labels, sentence_snap, boundaries
from .llama import server, HERE
from .text import parse

OUT = HERE / "results" / "diarfix"


def prep(seed: int = 0):
    items = []
    for d in load_dialogs():
        words, gold, noisy = inject_boundary_errors(d, seed=seed)
        marks = [t.mark for u in d.turns for t in parse(u.text)]
        items.append((d, words, gold, noisy, marks, max(gold)))
    return items


def baseline():
    tot = dict(words=0, noisy=0, snap=0)
    for d, words, gold, noisy, marks, k in prep():
        tot["words"] += len(words)
        tot["noisy"] += wer_labels(gold, noisy)
        tot["snap"] += wer_labels(gold, sentence_snap(marks, noisy))
    return tot


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="+", required=True)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--modes", nargs="+", default=["grammar", "free"])
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    items = prep(a.seed)
    print("baseline", baseline())
    for model in a.models:
        for mode in a.modes:
            path = OUT / f"{model}__{mode}.json"
            if path.exists() and not a.force:
                continue
            res = {"model": model, "mode": mode, "threads": a.threads, "dialogs": [], "baseline": baseline()}
            with server(model, threads=a.threads) as srv:
                for d, words, gold, noisy, marks, k in items:
                    labels, st = fix_dialog(srv, model, words, noisy, k, use_grammar=(mode == "grammar"))
                    fixed = sum(1 for g, n, l in zip(gold, noisy, labels) if n != g and l == g)
                    broken = sum(1 for g, n, l in zip(gold, noisy, labels) if n == g and l != g)
                    res["dialogs"].append(dict(id=d.id, words=len(words), boundaries=len(boundaries(noisy)),
                                               err_before=wer_labels(gold, noisy), err_after=wer_labels(gold, labels),
                                               fixed=fixed, broken=broken, **st))
                    print(model, mode, d.id, res["dialogs"][-1], flush=True)
            path.write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
