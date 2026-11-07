"""Speaker-label correction at turn boundaries (DiarizationLM-like), text-only, words locked by GBNF."""
from __future__ import annotations

import re
import time

from .llama import Server, chatml, CATALOG, load_json, load_text
from .text import guard_ok

R = 8  # window radius in words


def tagged(words: list[str], labels: list[int]) -> str:
    out, cur = [], None
    for w, l in zip(words, labels):
        if l != cur:
            out.append(f"[{l}]")
            cur = l
        out.append(w)
    return " ".join(out)


def parse_tagged(text: str, n_words: int | None = None) -> tuple[list[str], list[int]]:
    words, labels, cur = [], [], 1
    for tok in text.split():
        m = re.fullmatch(r"\[(\d+)\]", tok)
        if m:
            cur = int(m.group(1))
        else:
            words.append(tok)
            labels.append(cur)
    return words, labels


def window_grammar(words: list[str], k: int) -> str:
    digits = f"[1-{k}]"
    parts = [f'"[" {digits} "] "']
    for i, w in enumerate(words):
        parts.append(f'"{w}"')
        if i < len(words) - 1:
            parts.append(f'(" " | " [" {digits} "] ")')
    return "root ::= " + " ".join(parts) + "\n"


def messages(window_text: str) -> list[dict]:
    msgs = [{"role": "system", "content": load_text("diarfix_system.txt")}]
    for ex in load_json("diarfix_fewshot.json"):
        msgs += [{"role": "user", "content": ex["in"]}, {"role": "assistant", "content": ex["out"]}]
    msgs.append({"role": "user", "content": window_text})
    return msgs


def boundaries(labels: list[int]) -> list[int]:
    return [i for i in range(1, len(labels)) if labels[i] != labels[i - 1]]


def fix_dialog(srv: Server, model: str, words: list[str], noisy: list[int], k: int, use_grammar: bool = True):
    """Sequentially fix every boundary of `noisy`; returns (labels, stats)."""
    family = CATALOG[model][1]
    labels = list(noisy)
    stats = {"calls": 0, "prompt_ms": 0.0, "predicted_ms": 0.0, "prompt_n": 0, "predicted_n": 0, "guard_fail": 0}
    for b in boundaries(noisy):
        lo, hi = max(0, b - R), min(len(words), b + R)
        w = words[lo:hi]
        inp = tagged(w, labels[lo:hi])
        grammar = window_grammar(w, k) if use_grammar else None
        r = srv.complete(chatml(messages(inp), family), n_predict=len(w) * 5 + 16, grammar=grammar)
        stats["calls"] += 1
        for key in ("prompt_ms", "predicted_ms", "prompt_n", "predicted_n"):
            stats[key] += r[key]
        ow, ol = parse_tagged(r["text"])
        if ow != w:
            stats["guard_fail"] += 1
            continue
        labels[lo:hi] = ol
    return labels, stats


def wer_labels(gold: list[int], pred: list[int]) -> int:
    return sum(g != p for g, p in zip(gold, pred))


def sentence_snap(words_marks: list[str], noisy: list[int], max_shift: int = 4) -> list[int]:
    """No-LLM baseline: move each boundary to the nearest sentence end (mark in . ? !) within max_shift words."""
    labels = list(noisy)
    for b in boundaries(noisy):
        best = None
        for d in range(0, max_shift + 1):
            for cand in (b - d, b + d):
                if 0 < cand < len(labels) and words_marks[cand - 1] in (".", "?", "!"):
                    best = cand
                    break
            if best is not None:
                break
        if best is None or best == b:
            continue
        a_lab, b_lab = noisy[b - 1], noisy[b]
        if best < b:
            for i in range(best, b):
                labels[i] = b_lab
        else:
            for i in range(b, best):
                labels[i] = a_lab
    return labels
