"""CLI: python -m bench_llm.run_punct --models qwen3-1.7b-q8_0 --variants a b c"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

from .data import load_dialogs, load_fleurs
from .llama import server, HERE
from .punct import evaluate

OUT = HERE / "results" / "punct"


def eval_set(n_fleurs: int, stride: int = 2):
    f = load_fleurs("test", n_fleurs, seed=1)
    d = [u for dl in load_dialogs() for u in dl.turns]
    d = d[::stride]
    return f, d


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="+", required=True)
    ap.add_argument("--variants", nargs="+", default=["a", "b", "c"])
    ap.add_argument("--n-fleurs", type=int, default=30)
    ap.add_argument("--dialog-stride", type=int, default=2)
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--tag", default="")
    ap.add_argument("--lang", default="ru", choices=["ru", "en"])
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    if a.lang == "en":
        a.tag = a.tag or "_en"
        fleurs, dialog = load_fleurs("test", a.n_fleurs, seed=1, lang="en"), []
    else:
        fleurs, dialog = eval_set(a.n_fleurs, a.dialog_stride)
    for model in a.models:
        todo = [v for v in a.variants if a.force or not (OUT / f"{model}__{v}{a.tag}.json").exists()]
        if not todo:
            continue
        with server(model, threads=a.threads) as srv:
            for v in todo:
                res = {"threads": a.threads, "load_s": srv.load_s}
                for name, utts in (("fleurs", fleurs), ("dialog", dialog)):
                    if not utts:
                        continue
                    t0 = time.time()
                    res[name] = evaluate(srv, model, v, utts, lang=a.lang)
                    print(f"{model} {v} {name}: F1 marks={res[name]['f1']['marks']:.3f} caps={res[name]['f1']['caps']:.3f} "
                          f"guard={res[name]['guard_pass']:.2f} {res[name]['ms_per_word']:.0f} ms/word wall={time.time()-t0:.0f}s", flush=True)
                (OUT / f"{model}__{v}{a.tag}.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
