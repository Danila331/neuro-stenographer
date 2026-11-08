import json
import os
from pathlib import Path

from bench_llm.android_flow import ask, combine, lines_of
from bench_llm.data import load_dialogs
from bench_llm.llama import HERE, server
from bench_llm.text import norm

ROOT = Path(__file__).resolve().parents[1]
TAG = os.environ.get("QA_TAG", "focused2500_qa025_long")
START = int(os.environ.get("QA_START", "0"))
END = int(os.environ.get("QA_END", "12"))

gold = json.loads((ROOT / "src/eval/bench_llm/data/protocol_gold.json").read_text(encoding="utf-8"))
questions = [q for group in gold.values() for q in group["qa"]][START:END]
items = combine(load_dialogs())[0]
lines = lines_of(items)
with server("app-qwen3-1.7b-q4_k_m", threads=4, n_ctx=4096,
            extra=["-nr", "-fa", "on", "-ctk", "q8_0", "-ctv", "q8_0"]) as srv:
    out = []
    for index, question in enumerate(questions, START + 1):
        answer, stats = ask(srv, Path("prompts/android"), 4096, lines, question["q"])
        ok = any(norm(k) in norm(answer) for k in question["any"])
        out.append({"q": question["q"], "a": answer, "ok": ok, "sec": stats.sec,
                    "prompt_n": stats.prompt_n, "gen_n": stats.gen_n})
        print(f"{index}/{END} ok={ok} sec={stats.sec:.1f} prompt={stats.prompt_n} gen={stats.gen_n}", flush=True)

result = {"start": START, "end": END, "qa": out, "ok": sum(x["ok"] for x in out),
          "total": len(out), "avg_sec": sum(x["sec"] for x in out) / len(out),
          "avg_prompt_n": sum(x["prompt_n"] for x in out) / len(out)}
path = HERE / "results" / "android_flow" / f"app-qwen3-1.7b-q4_k_m__{TAG}.json"
path.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
print(json.dumps({k: v for k, v in result.items() if k != "qa"}, ensure_ascii=False))
