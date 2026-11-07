"""CLI: python -m bench_llm.run_android_flow --model app-qwen3-1.7b-q4_k_m --prompts prompts/android_dev/v1 --n-ctx 4096 --tag v1"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from .android_flow import protocol, protocol_merge, filter_quotes, ask, check, combine, lines_of
from .text import norm
from .data import load_dialogs
from .llama import server, HERE

OUT = HERE / "results" / "android_flow"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--prompts", required=True)
    ap.add_argument("--n-ctx", type=int, default=4096)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--sets", nargs="+", default=["d1_budget", "d2_bridge", "d3_holiday", "long"])
    ap.add_argument("--no-qa", action="store_true")
    ap.add_argument("--merge", action="store_true", help="long records: per-fragment protocols merged in code (proposal)")
    ap.add_argument("--flags", default="", help="server flags as one string, e.g. '-nr -lm none'")
    a = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    gold = json.loads((HERE / "data" / "protocol_gold.json").read_text(encoding="utf-8"))
    dl = {d.id: d for d in load_dialogs()}
    sets = {}
    for s in a.sets:
        if s == "long":
            sets[s] = combine(list(dl.values()))[0]
        else:
            sets[s] = combine([dl[s]])[0]
    gold_long = {"items": [i for g in gold.values() for i in g["items"]], "qa": [q for g in gold.values() for q in g["qa"]]}
    gold["long"] = gold_long
    pdir = Path(a.prompts)
    res = {"model": a.model, "prompts": str(pdir), "n_ctx": a.n_ctx, "threads": a.threads, "flags": a.flags, "sets": {}}
    with server(a.model, threads=a.threads, n_ctx=a.n_ctx, extra=a.flags.split()) as srv:
        for name, items in sets.items():
            lines = lines_of(items)
            text, st, n_frag = (protocol_merge if a.merge else protocol)(srv, pdir, a.n_ctx, lines)
            chk = check(text, items, gold[name]["items"])
            ftext = filter_quotes(text, norm(" ".join(u.text for _, u in items)))
            chk["quotes_after_filter"] = len(__import__("re").findall(r"«[^»]{8,}»", ftext))
            entry = {"protocol": text, "check": chk, "fragments": n_frag, "stats": st.__dict__, "sec": st.sec}
            print(f"[{a.tag}] {name}: frags={n_frag} {st.sec:.0f}s prompt {st.prompt_n} gen {st.gen_n} | facts {chk['facts_recall']:.2f} tc {chk['timecodes_valid']}/{chk['timecodes']} quotes {chk['quotes_verbatim']}/{chk['quotes']} names {chk['invented_names']}", flush=True)
            if not a.no_qa:
                qa = []
                for g in gold[name]["qa"]:
                    ans, qs = ask(srv, pdir, a.n_ctx, lines, g["q"])
                    ok = any(__import__("bench_llm.text", fromlist=["norm"]).norm(k) in __import__("bench_llm.text", fromlist=["norm"]).norm(ans) for k in g["any"])
                    qa.append({"q": g["q"], "a": ans, "ok": ok, "sec": qs.sec, "prompt_n": qs.prompt_n, "gen_n": qs.gen_n})
                entry["qa"] = qa
                print(f"[{a.tag}] {name}: QA {sum(x['ok'] for x in qa)}/{len(qa)}  avg {sum(x['sec'] for x in qa)/len(qa):.1f}s", flush=True)
            res["sets"][name] = entry
    (OUT / f"{a.model}__{a.tag}.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
