"""Просодическая пунктуация поверх безпунктуационной модели (GigaAM v3 CTC): знаки по длине паузы между словами.

Слова и времена — из токенов/меток CTC (words_from_tokens). Правила (пороги подбираются на dev):
  пауза >= t_period      -> '.' после слова (следующее слово с заглавной)
  t_comma <= пауза < t_period -> ','
  конец реплики           -> '.' (или '?' если последнее слово — вопросительное: опция qwords)
  первое слово реплики    -> с заглавной
"""
from __future__ import annotations
from .words import words_from_tokens

QWORDS = {"кто", "что", "где", "когда", "куда", "откуда", "почему", "зачем", "как", "какой", "какая", "какое", "какие", "сколько",
          "ли", "чей", "разве", "неужели"}


def punctuate(tokens: list[str], ts: list[float], t_comma: float = 0.25, t_period: float = 0.6, question: bool = False,
              frame: float = 0.04) -> str:
    words = words_from_tokens(tokens, ts, frame=frame)
    if not words:
        return ""
    out = []
    cap = True
    for i, (w, s, e) in enumerate(words):
        if cap:
            w = w[:1].upper() + w[1:]
            cap = False
        if i + 1 < len(words):
            gap = words[i + 1][1] - e
            if gap >= t_period:
                w += "."; cap = True
            elif gap >= t_comma:
                w += ","
        else:
            q = question and words[0][0].lower() in QWORDS
            w += "?" if q else "."
        out.append(w)
    return " ".join(out)
