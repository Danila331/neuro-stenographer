"""Replica of the app's LLM flow (LlmController/PromptBuilder): plain-text protocol (map-reduce for long records) and Q&A.
Sampling is greedy exactly as in llm_jni.cpp. Port of the Kotlin helpers is 1:1."""
from __future__ import annotations

import re
import time
from dataclasses import dataclass, field
from pathlib import Path

from .data import Dialog, Utt
from .llama import Server, PROMPTS
from .text import norm

CHARS_PER_TOKEN = 2.3
PROTOCOL_MAX, NOTES_MAX, QA_MAX = 700, 320, 400


def clock(ms: int) -> str:
    s = max(0, ms) // 1000
    h, m, sec = s // 3600, (s % 3600) // 60, s % 60
    return f"{h}:{m:02d}:{sec:02d}" if h else f"{m:02d}:{sec:02d}"


def combine(dialogs: list[Dialog], gap_ms: int = 4000) -> tuple[list[tuple[int, Utt]], str]:
    """Concatenate dialogues into one long record: [(start_ms, utt)] with time offsets."""
    out, off = [], 0
    for d in dialogs:
        for u, (a, b) in zip(d.turns, d.times):
            out.append((off + a, u))
        off += d.times[-1][1] + gap_ms
    return out, "+".join(d.id for d in dialogs)


def lines_of(items: list[tuple[int, Utt]]) -> list[str]:
    return [f"[{clock(t)}] Говорящий {u.speaker}: {u.text}" for t, u in items]


def content_budget(n_ctx: int, max_new: int, system_chars: int) -> int:
    tokens = n_ctx - max_new - 48 - int(system_chars / CHARS_PER_TOKEN)
    return int(max(tokens, 256) * CHARS_PER_TOKEN)


def chunk(lines: list[str], budget: int) -> list[str]:
    out, cur = [], ""
    for raw in lines:
        line = raw if len(raw) <= budget else raw[: budget - 1] + "…"
        if cur and len(cur) + len(line) + 1 > budget:
            out.append(cur)
            cur = ""
        cur = (cur + "\n" + line) if cur else line
    if cur:
        out.append(cur)
    return out


def _stems(text: str) -> set[str]:
    return {w[:5] for w in re.findall(r"[\w]+", text.lower().replace("ё", "е")) if len(w) >= 4}


def select_relevant(chunks: list[str], question: str, budget: int) -> list[str]:
    if sum(len(c) for c in chunks) <= budget:
        return chunks
    keys = _stems(question)
    scored = sorted(((i, sum(1 for k in keys if k in _stems(c))) for i, c in enumerate(chunks)), key=lambda x: (-x[1], x[0]))
    chosen, used = [], 0
    for i, _ in scored:
        if used + len(chunks[i]) > budget:
            continue
        chosen.append(i)
        used += len(chunks[i])
    if not chosen:
        chosen.append(scored[0][0])
    return [chunks[i] for i in sorted(chosen)]


def clean(raw: str) -> str:
    s = re.sub(r"(?s)<think>.*?</think>", "", raw)
    s = s.replace("<|im_end|>", "").replace("<|im_start|>", "")
    s = re.sub(r"</?[A-Za-z_][A-Za-z0-9_]*>", "", s).replace("**", "")
    return re.sub(r"\n{3,}", "\n\n", s).strip()


def runaway_index(ans: str) -> int:
    m = re.search(r"\n\s*(Вопрос|Question|Q)\s*:", ans)
    return m.start() if m and m.start() >= 12 else -1


def chatml(system: str, user: str) -> str:
    return f"<|im_start|>system\n{system.strip()}<|im_end|>\n<|im_start|>user\n{user.strip()}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"


@dataclass
class Stats:
    prompt_n: int = 0
    prompt_ms: float = 0.0
    gen_n: int = 0
    gen_ms: float = 0.0
    calls: int = 0

    def add(self, r: dict):
        self.prompt_n += r["prompt_n"]; self.prompt_ms += r["prompt_ms"]
        self.gen_n += r["predicted_n"]; self.gen_ms += r["predicted_ms"]; self.calls += 1

    @property
    def sec(self) -> float:
        return (self.prompt_ms + self.gen_ms) / 1000


def read(dir_: Path, name: str) -> str:
    return (dir_ / name).read_text(encoding="utf-8").strip()


def gen(srv: Server, prompt: str, max_new: int, stats: Stats, stop_runaway: bool = False) -> str:
    r = srv.complete(prompt, n_predict=max_new, extra={"cache_prompt": False})
    stats.add(r)
    return r["text"]


def protocol(srv: Server, pdir: Path, n_ctx: int, lines: list[str]) -> tuple[str, Stats, int]:
    st = Stats()
    system = read(pdir, "protocol_system.txt")
    budget = content_budget(n_ctx, PROTOCOL_MAX, len(system))
    chunks = chunk(lines, budget)
    material = "\n".join(chunks)
    n_frag = 1
    if len(chunks) > 1:
        notes_sys = read(pdir, "protocol_notes_system.txt")
        nb = content_budget(n_ctx, NOTES_MAX, len(notes_sys))
        frag = chunk(lines, nb)
        n_frag = len(frag)
        notes = [clean(gen(srv, chatml(notes_sys, f"Фрагмент {i + 1} из {len(frag)}:\n{part}"), NOTES_MAX, st)) for i, part in enumerate(frag)]
        per = budget // max(1, len(frag))
        material = "\n\n".join(f"Часть {i + 1}:\n" + n[:per] for i, n in enumerate(notes))
    out = clean(gen(srv, chatml(system, f"Расшифровка:\n{material}"), PROTOCOL_MAX, st))
    return out, st, n_frag


def ask(srv: Server, pdir: Path, n_ctx: int, lines: list[str], q: str) -> tuple[str, Stats]:
    st = Stats()
    system = read(pdir, "qa_system.txt")
    budget = content_budget(n_ctx, QA_MAX, len(system) + len(q))
    chunks = chunk(lines, budget)
    material = "\n...\n".join(select_relevant(chunks, q, budget))
    full = gen(srv, chatml(system, f"Расшифровка:\n{material}\n\nВопрос: {q}"), QA_MAX, st)
    i = runaway_index(full)
    return clean(full[:i] if i >= 0 else full) or "Модель не дала ответа.", st


# ----------------------------------------------------------------------------- automatic checks
HEADINGS = {"резюме", "вопросы", "решения", "поручения", "цитаты", "часть", "говорящий", "нет", "в", "это", "кто"}


def check(text: str, items: list[tuple[int, Utt]], gold_items: list[dict]) -> dict:
    full = norm(" ".join(u.text for _, u in items))
    starts = {clock(t) for t, _ in items}
    tcs = re.findall(r"\b\d{1,2}:\d{2}\b", text)
    quotes = [q for q in re.findall(r"«([^»]{8,})»", text)]
    q_ok = [norm(q) in full for q in quotes]
    vocab = _stems(" ".join(u.text for _, u in items))
    names = set()
    for m in re.finditer(r"(?<![.!?:\n])(?<!^)\s([А-ЯЁ][а-яё]{3,})", text):
        w = m.group(1)
        if w.lower() not in HEADINGS and w.lower().replace("ё", "е")[:5] not in vocab:
            names.add(w)
    low = norm(text)
    hits = [any(norm(k) in low for k in it["any"]) for it in gold_items]
    heads = [h for h in ("Резюме", "Вопросы", "Решения", "Поручения", "Цитаты") if h in text]
    return {"facts_recall": sum(hits) / max(1, len(hits)), "timecodes": len(tcs), "timecodes_valid": sum(t in starts or t.lstrip("0") in {s.lstrip("0") for s in starts} for t in tcs),
            "quotes": len(quotes), "quotes_verbatim": sum(q_ok), "invented_names": sorted(names), "sections": len(heads), "chars": len(text)}


# ----------------------------------------------------------------------------- proposal: per-fragment protocols + merge in code
SECTIONS = ["Резюме", "Вопросы", "Решения", "Поручения", "Цитаты"]
FRAG_MAX = 450


def split_sections(text: str) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {s: [] for s in SECTIONS}
    cur = None
    for raw in text.splitlines():
        line = raw.strip()
        m = re.match(r"^(Резюме|Вопросы|Решения|Поручения|Цитаты)\s*:?\s*(.*)$", line)
        if m:
            cur = m.group(1)
            if m.group(2):
                out[cur].append(m.group(2))
            continue
        if cur and line:
            out[cur].append(line.lstrip("- ").strip())
    return out


def merge_protocols(parts: list[str]) -> str:
    secs = [split_sections(p) for p in parts]
    lines = []
    resume = " ".join(" ".join(s["Резюме"]).strip() for s in secs).strip()
    lines.append("Резюме: " + resume)
    for name in SECTIONS[1:]:
        seen, items = set(), []
        for s in secs:
            for it in s[name]:
                k = norm(it)
                if not k or k == "нет" or k in seen:
                    continue
                seen.add(k)
                items.append(it)
        lines.append(f"{name}:")
        lines += [f"- {it}" for it in items] or ["- нет"]
    return "\n".join(lines)


def protocol_merge(srv: Server, pdir: Path, n_ctx: int, lines: list[str]) -> tuple[str, Stats, int]:
    st = Stats()
    system = read(pdir, "protocol_system.txt")
    frags = chunk(lines, content_budget(n_ctx, FRAG_MAX, len(system)))
    outs = [clean(gen(srv, chatml(system, f"Расшифровка (часть {i + 1} из {len(frags)}):\n{part}"), FRAG_MAX, st)) for i, part in enumerate(frags)]
    return (outs[0] if len(outs) == 1 else merge_protocols(outs)), st, len(frags)


def filter_quotes(text: str, transcript_norm: str) -> str:
    """Post-process proposal: drop quote bullets that are not verbatim substrings of the transcript."""
    keep = []
    for line in text.splitlines():
        m = re.search(r"«([^»]{8,})»", line)
        if m and line.lstrip().startswith("-") and norm(m.group(1)) not in transcript_norm:
            continue
        keep.append(line)
    return "\n".join(keep)
