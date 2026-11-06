"""Punctuation + case restoration with an LLM: free generation (a), GBNF word-lock (b), compact positions (c)."""
from __future__ import annotations

import json
import re
import time
from dataclasses import dataclass, asdict

from .llama import Server, chatml, CATALOG, load_json, load_text
from .text import Tok, parse, render, guard_ok, fallback, score, total, MARKS


# ---------------------------------------------------------------- shared helpers
def fix_sentence_case(toks: list[Tok]) -> list[Tok]:
    """Deterministic post-fix: first word and the word after . ? ! are capitalised."""
    for i, t in enumerate(toks):
        if i == 0 or toks[i - 1].mark in (".", "?", "!"):
            t.cap = True
    return toks


def esc(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"')


def words_grammar(words: list[str]) -> str:
    """GBNF that admits ONLY the given words in order; each word lower or Capitalised, optional mark after it."""
    parts = []
    for i, w in enumerate(words):
        alts = f'"{esc(w)}"' if not w[:1].isalpha() else f'"{esc(w)}" | "{esc(w[:1].upper() + w[1:])}"'
        parts.append(f"({alts}) mark")
    return 'root ::= ' + ' " " '.join(parts) + '\nmark ::= ("," | "." | "?" | "!" | " —")?\n'


COMPACT_GRAMMAR = (
    'root ::= (num mark " ")* "|" (" " num)*\n'
    'num ::= [1-9] [0-9]?\n'
    'mark ::= "," | "." | "?" | "!" | "—"\n'
)


def numbered(words: list[str]) -> str:
    return " ".join(f"{i + 1}:{w}" for i, w in enumerate(words))


def compact_out(toks: list[Tok]) -> str:
    marks = " ".join(f"{i + 1}{t.mark}" for i, t in enumerate(toks) if t.mark)
    caps = " ".join(str(i + 1) for i, t in enumerate(toks)
                    if t.cap and i > 0 and toks[i - 1].mark not in (".", "?", "!"))
    return f"{marks} |{(' ' + caps) if caps else ''}"


def parse_compact(words: list[str], out: str) -> list[Tok]:
    toks = [Tok(w, False, "") for w in words]
    head, _, tail = out.partition("|")
    for m in re.finditer(r"(\d+)\s*([,.?!—])", head):
        i = int(m.group(1)) - 1
        if 0 <= i < len(toks):
            toks[i].mark = m.group(2)
    for m in re.finditer(r"\d+", tail):
        i = int(m.group(0)) - 1
        if 0 <= i < len(toks):
            toks[i].cap = True
    if toks and not toks[-1].mark:
        toks[-1].mark = "."
    return toks


# ---------------------------------------------------------------- prompts
def build_messages(variant: str, words: list[str]) -> list[dict]:
    if variant in ("a", "b"):
        sys_ = load_text("punct_system.txt")
        msgs = [{"role": "system", "content": sys_}]
        for ex in load_json("punct_fewshot.json"):
            msgs += [{"role": "user", "content": ex["in"]}, {"role": "assistant", "content": ex["out"]}]
        msgs.append({"role": "user", "content": " ".join(words)})
        return msgs
    if variant == "c":
        sys_ = load_text("punct_compact_system.txt")
        msgs = [{"role": "system", "content": sys_}]
        for ex in load_json("punct_fewshot.json"):
            w = ex["in"].split()
            msgs += [{"role": "user", "content": numbered(w)},
                     {"role": "assistant", "content": compact_out(parse(ex["out"]))}]
        msgs.append({"role": "user", "content": numbered(words)})
        return msgs
    raise ValueError(variant)


@dataclass
class Rec:
    id: str
    n_words: int
    out_raw: str
    guard_ok: bool
    prompt_n: int
    prompt_ms: float
    predicted_n: int
    predicted_ms: float
    wall_ms: float
    counts_final: dict | None = None  # filled by caller


def run_utt(srv: Server, family: str, variant: str, words: list[str]) -> tuple[list[Tok], dict, bool, str]:
    prompt = chatml(build_messages(variant, words), family)
    n_pred = max(32, 4 * len(words) + 16) if variant != "c" else max(32, 3 * len(words))
    if variant == "b":
        r = srv.complete(prompt, n_pred + 24, grammar=words_grammar(words))
    elif variant == "c":
        r = srv.complete(prompt, n_pred, grammar=COMPACT_GRAMMAR)
    else:
        r = srv.complete(prompt, n_pred + 24)
    out = r["text"]
    if variant == "c":
        toks = parse_compact(words, out)
        ok = True
    else:
        ok = guard_ok(" ".join(words), out)
        toks = parse(out) if ok else fallback(words)
    return fix_sentence_case(toks), r, ok, out


def evaluate(srv: Server, model: str, variant: str, utts: list, progress=None) -> dict:
    family = CATALOG[model][1]
    recs, counts = [], []
    # warm the prefix cache (first request pays the prefix prefill): report separately
    first = None
    for k, u in enumerate(utts):
        toks, r, ok, out = run_utt(srv, family, variant, u.words)
        gold = fix_sentence_case(parse(u.text))
        assert [t.w for t in gold] == u.words
        c = score(gold, toks)
        counts.append(c)
        rec = dict(id=u.id, n_words=len(u.words), out=out, guard_ok=ok, prompt_n=r["prompt_n"], prompt_ms=r["prompt_ms"],
                   predicted_n=r["predicted_n"], predicted_ms=r["predicted_ms"], wall_ms=r["wall_ms"], cache_n=r["cache_n"])
        recs.append(rec)
        if k == 0:
            first = rec
        if progress:
            progress(k + 1, len(utts))
    tot = total(counts)
    body = recs[1:] if len(recs) > 1 else recs  # steady state: exclude cold-prefix request
    words = sum(r["n_words"] for r in body)
    ms = sum(r["prompt_ms"] + r["predicted_ms"] for r in body)
    return {
        "model": model, "variant": variant, "n_utts": len(utts), "n_words": sum(r["n_words"] for r in recs),
        "guard_pass": sum(r["guard_ok"] for r in recs) / len(recs),
        "f1": {k: round(v.f1, 4) for k, v in tot.items()},
        "prec": {k: round(v.p, 4) for k, v in tot.items()},
        "rec": {k: round(v.r, 4) for k, v in tot.items()},
        "support": {k: v.tp + v.fn for k, v in tot.items()},
        "ms_per_word": ms / words if words else 0,
        "s_per_1000_words": ms / words if words else 0,   # ms/word == s/1000 words
        "gen_tokens_per_word": sum(r["predicted_n"] for r in body) / words if words else 0,
        "prompt_tokens_per_word": sum(r["prompt_n"] for r in body) / words if words else 0,
        "decode_tok_s": (sum(r["predicted_n"] for r in body) / (sum(r["predicted_ms"] for r in body) / 1000)) if body else 0,
        "prefill_tok_s": (sum(r["prompt_n"] for r in body) / (sum(r["prompt_ms"] for r in body) / 1000)) if body else 0,
        "cold_prefix_ms": first["prompt_ms"] if first else 0, "cold_prefix_tokens": first["prompt_n"] if first else 0,
        "records": recs,
    }
