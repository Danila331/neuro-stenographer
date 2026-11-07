"""CLI: python -m bench_llm.run_speed --models ... --threads 4  -> results/speed/<model>_t<threads>.json (llama-bench, CPU only)"""
from __future__ import annotations

import argparse
import json
import os
import subprocess

from .llama import BIN, HERE, model_path, CATALOG

OUT = HERE / "results" / "speed"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="+", default=list(CATALOG))
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--reps", type=int, default=3)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--repack", type=int, default=1)
    ap.add_argument("--tag", default="")
    a = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    for m in a.models:
        p = OUT / f"{m}_t{a.threads}{a.tag}.json"
        if (p.exists() and not a.force) or not model_path(m).exists():
            continue
        r = subprocess.run([str(BIN / "llama-bench"), "-m", str(model_path(m)), "-p", "256", "-n", "64", "-t", str(a.threads),
                            "-ngl", "0", "--repack", str(a.repack), "-r", str(a.reps), "-o", "json"], capture_output=True, text=True,
                           env={**os.environ, "DYLD_LIBRARY_PATH": str(BIN)})
        try:
            rows = json.loads(r.stdout)
        except Exception:
            print(m, "bench failed", r.stderr[-500:])
            continue
        res = {"repack": a.repack, "model": m, "threads": a.threads, "size_bytes": model_path(m).stat().st_size,
               "load_avg": os.getloadavg(),
               "pp256_tok_s": next(x["avg_ts"] for x in rows if x["n_prompt"] > 0), "pp256_std": next(x["stddev_ts"] for x in rows if x["n_prompt"] > 0),
               "tg64_tok_s": next(x["avg_ts"] for x in rows if x["n_gen"] > 0), "tg64_std": next(x["stddev_ts"] for x in rows if x["n_gen"] > 0)}
        p.write_text(json.dumps(res, indent=1))
        print(res, flush=True)


if __name__ == "__main__":
    main()
