"""CLI: python -m bench_llm.run_rupunct  -> results/punct/rupunct-<size>-<backend>.json"""
from __future__ import annotations

import json
import sys

from .rupunct import RuPunct, evaluate, export_onnx
from .run_punct import eval_set, OUT


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    fleurs, dialog = eval_set(40, None)
    for size in ("small", "medium"):
        export_onnx(size)
        for backend in ("torch", "onnx-fp32", "onnx-int8"):
            rp = RuPunct(size, backend, threads=4)
            rp.punctuate(["привет", "как", "дела"])  # warmup
            res = {"fleurs": evaluate(rp, fleurs), "dialog": evaluate(rp, dialog), "threads": 4}
            for k in ("fleurs", "dialog"):
                print(size, backend, k, "marks F1", res[k]["f1"]["marks"], "caps", res[k]["f1"]["caps"], f"{res[k]['ms_per_word']:.2f} ms/word", flush=True)
            (OUT / f"rupunct-{size}-{backend}__base.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
