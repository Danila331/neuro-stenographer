"""CLI: python -m bench_llm.run_protocol --models qwen3-4b-q4_k_m"""
from __future__ import annotations

import argparse
import json

from .data import load_dialogs
from .llama import server, HERE, PROMPTS
from .protocol import run_protocol, run_qa

OUT = HERE / "results" / "protocol"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="+", required=True)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--n-ctx", type=int, default=4096)
    ap.add_argument("--no-grammar", action="store_true")
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    gold = json.loads((HERE / "data" / "protocol_gold.json").read_text(encoding="utf-8"))
    grammar = None if a.no_grammar else (PROMPTS / "protocol.gbnf").read_text(encoding="utf-8")
    dialogs = [d for d in load_dialogs() if d.id in gold]
    for model in a.models:
        path = OUT / f"{model}{'__nogrammar' if a.no_grammar else ''}.json"
        if path.exists() and not a.force:
            continue
        res = {"model": model, "threads": a.threads, "protocol": {}, "qa": {}}
        with server(model, threads=a.threads, n_ctx=a.n_ctx) as srv:
            for d in dialogs:
                p = run_protocol(srv, model, d, gold[d.id], grammar)
                res["protocol"][d.id] = p
                print(model, d.id, "valid", p["valid_json"], p["check"], f"prompt {p['prompt_n']} tok {p['prompt_ms']/1000:.1f}s, gen {p['predicted_n']} tok {p['predicted_ms']/1000:.1f}s", flush=True)
                q = run_qa(srv, model, d, gold[d.id]["qa"][:3])
                res["qa"][d.id] = q
                print(model, d.id, "QA", sum(x["correct"] for x in q), "/", len(q), flush=True)
        path.write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
