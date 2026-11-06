"""CLI: python -m bench_llm.run_mem --models ... --ctx 2048 4096 -> results/mem/<model>.json (peak RSS of llama-server, CPU only)"""
from __future__ import annotations

import argparse
import json
import subprocess
import threading
import time

from .data import load_dialogs
from .llama import server, HERE, CATALOG, chatml, model_path

OUT = HERE / "results" / "mem"


def rss_mb(pid: int) -> float:
    try:
        return int(subprocess.check_output(["ps", "-o", "rss=", "-p", str(pid)]).split()[0]) / 1024
    except Exception:
        return 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="+", default=list(CATALOG))
    ap.add_argument("--ctx", nargs="+", type=int, default=[2048, 4096])
    ap.add_argument("--kv", default="f16", choices=["f16", "q8_0"])
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--flags", default="", help="extra llama-server flags, e.g. '-nr'")
    ap.add_argument("--tag", default="")
    a = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    text = "\n".join(u.text for d in load_dialogs() for u in d.turns)
    for m in a.models:
        p = OUT / f"{m}{'' if a.kv == 'f16' else '__kv' + a.kv}{a.tag}.json"
        if not model_path(m).exists() or (p.exists() and not a.force):
            continue
        res = {"flags": a.flags, "model": m, "size_mb": model_path(m).stat().st_size / 1e6, "kv": a.kv, "ctx": {}}
        for n_ctx in a.ctx:
            extra = ([] if a.kv == "f16" else ["-fa", "on", "-ctk", "q8_0", "-ctv", "q8_0"]) + a.flags.split()
            with server(m, threads=a.threads, n_ctx=n_ctx, extra=extra) as srv:
                pid = srv.proc.pid
                idle = rss_mb(pid)
                peak = [idle]
                stop = threading.Event()

                def poll():
                    while not stop.is_set():
                        peak[0] = max(peak[0], rss_mb(pid))
                        time.sleep(0.15)
                th = threading.Thread(target=poll, daemon=True)
                th.start()
                # fill ~85% of the context with a real prompt, then generate 24 tokens
                ids = srv.tokenize(text)
                budget = int(n_ctx * 0.85)
                prompt = chatml([{"role": "user", "content": "Кратко перескажи:\n" + text[: len(text) * budget // max(1, len(ids))]}], CATALOG[m][1])
                r = srv.complete(prompt, n_predict=24)
                stop.set()
                th.join()
                res["ctx"][n_ctx] = {"idle_rss_mb": round(idle), "peak_rss_mb": round(peak[0]), "prompt_tokens": r["prompt_n"]}
                print(m, n_ctx, res["ctx"][n_ctx], flush=True)
        p.write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
