"""Нормализация текста и WER. Нормализация совпадает с ARCHITECTURE §6 (guard): lower, ё->е,
всё кроме букв/цифр -> пробел, схлопнуть пробелы."""
from __future__ import annotations

import sys
from pathlib import Path

# Нормализация — как в официальном харнессе (src/eval/stenoeval/textnorm.py), чтобы цифры совпадали с его отчётами.
_STENO = Path(__file__).resolve().parents[2] / "stenoeval"
try:
    sys.path.insert(0, str(_STENO))
    from stenoeval.textnorm import tokens as _tokens     # легковесный модуль (num2words — лениво)
except Exception:                                         # запасной вариант (та же логика: NFKC, lower, ё->е, не-буквы -> пробел)
    import unicodedata

    def _tokens(text: str, mode: str) -> list[str]:
        if mode == "raw":
            return text.split()
        t = unicodedata.normalize("NFKC", text).lower().replace("ё", "е")
        return "".join(c if unicodedata.category(c)[0] in "LNM" else " " for c in t).split()


_PC_TOKEN = None


def _pc_tokens(text: str, mode: str) -> list[str]:
    """Режимы «со знаками» (главная метрика после ответа жюри Q13/Q14): регистр и пунктуация сохраняются, только ё->е.
    pc       — знак — часть слова («день!» != «день»)
    pc_sep   — знаки отдельными токенами («день», «!»)
    *_num    — цифры предварительно записываются словами (как norm_num)
    """
    import re, unicodedata
    t = unicodedata.normalize("NFKC", text).replace("ё", "е").replace("Ё", "Е")
    if mode.endswith("_num"):
        from stenoeval.textnorm import spell_numbers
        t = spell_numbers(t)
        mode = mode[:-4]
    t = re.sub(r"[\u2010-\u2015]", "-", t)                 # разные тире -> '-'
    t = t.replace("…", "...")
    if mode == "pc":
        return t.split()
    if mode == "pc_sep":
        return re.findall(r"\w+(?:-\w+)*|[^\w\s]", t)
    raise ValueError(mode)


def tokens(text: str, mode: str) -> list[str]:
    return _pc_tokens(text, mode) if mode.startswith("pc") else _tokens(text, mode)


def norm(s: str) -> str:
    return " ".join(_tokens(s, "norm"))


def raw(s: str) -> str:
    return " ".join(_tokens(s, "raw"))


def edit_counts(ref: list[str], hyp: list[str]) -> tuple[int, int, int]:
    """(substitutions, deletions, insertions) по расстоянию Левенштейна на словах."""
    n, m = len(ref), len(hyp)
    prev = [(j, 0, 0, j) for j in range(m + 1)]  # (cost, S, D, I)
    for i in range(1, n + 1):
        cur = [(i, 0, i, 0)]
        for j in range(1, m + 1):
            if ref[i - 1] == hyp[j - 1]:
                c = prev[j - 1]
                cand_sub = (c[0], c[1], c[2], c[3])
            else:
                c = prev[j - 1]
                cand_sub = (c[0] + 1, c[1] + 1, c[2], c[3])
            d = prev[j]
            cand_del = (d[0] + 1, d[1], d[2] + 1, d[3])
            ins = cur[j - 1]
            cand_ins = (ins[0] + 1, ins[1], ins[2], ins[3] + 1)
            cur.append(min(cand_sub, cand_del, cand_ins, key=lambda t: t[0]))
        prev = cur
    _, s, d, i = prev[m]
    return s, d, i


def wer_counts(ref: str, hyp: str, mode: str = "norm") -> tuple[int, int, int, int]:
    r, h = tokens(ref, mode), tokens(hyp, mode)
    s, d, i = edit_counts(r, h)
    return s, d, i, len(r)


def wer(refs: list[str], hyps: list[str], mode: str = "norm") -> float:
    S = D = I = N = 0
    for r, h in zip(refs, hyps):
        s, d, i, n = wer_counts(r, h, mode)
        S += s; D += d; I += i; N += n
    return (S + D + I) / max(N, 1)
