"""B51 answer step: build the context from retrieved chunks, ask the LLM, verify the citations. Kotlin: `AnswerBuilder`."""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

from .data import Record, fmt_time
from .llm import chatml
from .retrieval import Hit

PROMPTS = Path(__file__).resolve().parent.parent / "prompts" / "android"
CITE = re.compile(r"\[(\d{1,3}):(\d{2}),\s*Говорящий\s*(\d+)\]")
NOANS = "В найденных фрагментах этого нет."


def system(name: str = "search_answer_system.txt") -> str:
    return (PROMPTS / name).read_text(encoding="utf-8").strip("\n")


def context(hits: list[Hit], recs: dict[str, Record], max_chars: int = 3200) -> tuple[str, list[tuple[str, int, str]]]:
    """Text for the prompt + the list of citable (record, start_s, speaker) triples in it (order of appearance)."""
    by_rec: dict[str, list[Hit]] = {}
    for h in hits:
        by_rec.setdefault(h.chunk.record, []).append(h)
    multi = len(by_rec) > 1
    out, cites, n = [], [], 0
    for rid, hs in by_rec.items():
        if multi:
            out.append(f"Запись: {recs[rid].title or rid}")
        seen = set()
        for h in sorted(hs, key=lambda x: x.chunk.start_ms):
            for u in h.chunk.utts:
                if (rid, u) in seen:
                    continue
                seen.add((rid, u))
                ut = recs[rid].utts[u]
                ln = f"[{fmt_time(ut.start_ms)}, Говорящий {ut.spk}] {ut.text}"
                if n + len(ln) > max_chars:
                    break
                n += len(ln) + 1
                out.append(ln)
                cites.append((rid, ut.start_ms // 1000, ut.speaker))
    return "\n".join(out), cites


@dataclass
class Answer:
    text: str
    valid_cites: list[tuple[str, int, str]]
    bad_cites: int
    refused: bool


def verify(text: str, cites: list[tuple[str, int, str]]) -> Answer:
    """Drop citations that do not point at a fragment from the context (time within 1 s, same speaker)."""
    ok, bad = [], 0

    def fix(m):
        nonlocal bad
        s = int(m[1]) * 60 + int(m[2])
        spk = f"speaker_{m[3]}"
        for rid, t, sp in cites:
            if sp == spk and abs(t - s) <= 1:
                ok.append((rid, t, sp))
                return m[0]
        bad += 1
        return ""
    clean = re.sub(r"\s+([.,;])", r"\1", CITE.sub(fix, text)).strip()
    return Answer(clean, ok, bad, NOANS[:20].lower() in text.lower())


def answer(srv, hits: list[Hit], recs: dict[str, Record], question: str, max_new: int = 160, prompt_file: str = "search_answer_system.txt") -> dict:
    ctx, cites = context(hits, recs)
    prompt = chatml(system(prompt_file), f"Фрагменты:\n{ctx}\n\nВопрос: {question}")
    r = srv.complete(prompt, n_predict=max_new, cache_prompt=True)
    return {"answer": verify(r["text"], cites), "raw": r["text"], "prompt_n": r["prompt_n"], "cache_n": r["cache_n"], "prompt_ms": r["prompt_ms"],
            "predicted_n": r["predicted_n"], "predicted_ms": r["predicted_ms"], "n_cites_ctx": len(cites)}
