"""Meeting protocol (JSON via GBNF) and question answering over a transcript."""
from __future__ import annotations

import json
import re

from .data import Dialog
from .llama import Server, chatml, CATALOG, load_text
from .text import norm


def mmss(ms: int) -> str:
    s = ms // 1000
    return f"{s // 60:02d}:{s % 60:02d}"


def transcript_text(d: Dialog) -> str:
    return "\n".join(f"[{mmss(t[0])}] speaker_{u.speaker}: {u.text}" for u, t in zip(d.turns, d.times))


def protocol_prompt(d: Dialog, family: str) -> str:
    msgs = [{"role": "system", "content": load_text("protocol_system.txt")},
            {"role": "user", "content": "Расшифровка:\n" + transcript_text(d) + "\n\nСоставь протокол в JSON."}]
    return chatml(msgs, family)


def qa_prompt(d: Dialog, q: str, family: str) -> str:
    msgs = [{"role": "system", "content": load_text("qa_system.txt")},
            {"role": "user", "content": "Расшифровка:\n" + transcript_text(d) + f"\n\nВопрос: {q}"}]
    return chatml(msgs, family)


def check_protocol(d: Dialog, obj: dict, gold: dict) -> dict:
    full = norm(" ".join(u.text for u in d.turns))
    starts = {mmss(t[0]) for t in d.times}
    by_start = {mmss(t[0]): u for u, t in zip(d.turns, d.times)}
    speakers = {f"speaker_{u.speaker}" for u in d.turns}
    quotes = obj.get("quotes", [])
    q_ver = [norm(q["text"]) in full for q in quotes]
    q_tc = []
    for q in quotes:
        u = by_start.get(q["time"])
        q_tc.append(bool(u) and norm(q["text"]) in norm(u.text))
    tcs = [x["time"] for k in ("decisions", "tasks", "quotes") for x in obj.get(k, [])]
    tc_valid = [t in starts for t in tcs]
    parts = {p["speaker"] for p in obj.get("participants", [])}
    dec_text = norm(" ".join(x["text"] for x in obj.get("decisions", []))) + " " + \
        norm(" ".join(x["text"] + " " + x["who"] for x in obj.get("tasks", [])))
    # numbers hallucination: digits in output that never occur in transcript digits/number words are not checked (manual review)
    hits = [any(norm(k) in dec_text for k in it["any"]) for it in gold["items"]]
    return {
        "quotes_n": len(quotes), "quotes_verbatim": sum(q_ver), "quotes_verbatim_with_right_time": sum(q_tc),
        "timecodes_n": len(tcs), "timecodes_valid": sum(tc_valid),
        "participants_recall": len(parts & speakers) / len(speakers), "participants_extra": len(parts - speakers),
        "facts_recall": sum(hits) / len(hits), "facts_n": len(hits),
        "n_decisions": len(obj.get("decisions", [])), "n_tasks": len(obj.get("tasks", [])), "n_topics": len(obj.get("topics", [])),
    }


def run_protocol(srv: Server, model: str, d: Dialog, gold: dict, grammar: str | None, max_new: int = 900):
    family = CATALOG[model][1]
    r = srv.complete(protocol_prompt(d, family), n_predict=max_new, grammar=grammar, extra={"repeat_penalty": 1.05})
    ok, obj, chk = True, None, None
    try:
        obj = json.loads(r["text"])
        chk = check_protocol(d, obj, gold)
    except Exception as e:  # noqa
        ok = False
    return {"valid_json": ok, "check": chk, "obj": obj, "raw": r["text"] if not ok else None,
            "prompt_n": r["prompt_n"], "prompt_ms": r["prompt_ms"], "predicted_n": r["predicted_n"],
            "predicted_ms": r["predicted_ms"], "stop": r["stop"]}


def run_qa(srv: Server, model: str, d: Dialog, qa: list[dict]):
    family = CATALOG[model][1]
    out = []
    for item in qa:
        r = srv.complete(qa_prompt(d, item["q"], family), n_predict=120)
        ans = norm(r["text"])
        out.append({"q": item["q"], "answer": r["text"], "correct": any(norm(k) in ans for k in item["any"]),
                    "prompt_ms": r["prompt_ms"], "predicted_ms": r["predicted_ms"], "prompt_n": r["prompt_n"],
                    "predicted_n": r["predicted_n"], "cache_n": r["cache_n"]})
    return out
