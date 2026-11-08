import json
import os
from pathlib import Path

from bench_llm.android_flow import ask, combine, lines_of
from bench_llm.data import load_dialogs
from bench_llm.llama import HERE, server
from bench_llm.text import norm

ROOT = Path(__file__).resolve().parents[1]
TAG = os.environ.get("QA_TAG", "focused2500_qa025_idf4_short")
SET_IDS = ("d1_budget", "d2_bridge", "d3_holiday")
gold = json.loads((ROOT / "src/eval/bench_llm/data/protocol_gold.json").read_text(encoding="utf-8"))
dialogs = {d.id: d for d in load_dialogs()}
sets = {name: {"items": combine([dialogs[name]])[0], "gold": gold[name]} for name in SET_IDS}
result = {"sets": {}, "ok": 0, "total": 0, "sec": 0.0, "prompt_n": 0}
with server("app-qwen3-1.7b-q4_k_m", threads=4, n_ctx=4096,
            extra=["-nr", "-fa", "on", "-ctk", "q8_0", "-ctv", "q8_0"]) as srv:
    for name, data in sets.items():
        lines = lines_of(data["items"])
        rows = []
        for question in data["gold"]["qa"]:
            answer, stats = ask(srv, Path("prompts/android"), 4096, lines, question["q"])
            ok = any(norm(k) in norm(answer) for k in question["any"])
            rows.append({"q": question["q"], "a": answer, "ok": ok, "sec": stats.sec,
                         "prompt_n": stats.prompt_n, "gen_n": stats.gen_n})
            result["ok"] += ok
            result["total"] += 1
            result["sec"] += stats.sec
            result["prompt_n"] += stats.prompt_n
        result["sets"][name] = {"qa": rows, "ok": sum(x["ok"] for x in rows), "total": len(rows)}
        print(f"{name}: {result['sets'][name]['ok']}/{len(rows)}", flush=True)
result["avg_sec"] = result["sec"] / result["total"]
result["avg_prompt_n"] = result["prompt_n"] / result["total"]
path = HERE / "results" / "android_flow" / f"app-qwen3-1.7b-q4_k_m__{TAG}.json"
path.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
print(json.dumps({k: v for k, v in result.items() if k != "sets"}, ensure_ascii=False))
