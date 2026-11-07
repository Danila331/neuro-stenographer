"""`postprocess.numbers = words`: digits -> Russian words in ASR words, before the Aligner.

Reference = Android `Postprocess.spellNumbers` (core/postprocess): the text of every word containing a digit goes
through `stenoeval.textnorm.spell_numbers` (num2words, ru: nominative, ordinals for "5-й", "3,5" -> "три запятая пять");
a word that becomes several words splits its interval evenly (a lone punctuation piece is glued to the previous
word); confidence is kept.  Needs the `stenoeval` package (and num2words) in the environment.
"""
from __future__ import annotations

from .types import Word


def _spell(text: str) -> str:
    try:
        from stenoeval.textnorm import spell_numbers
    except ImportError as e:  # pragma: no cover
        raise RuntimeError("postprocess.numbers=words needs the stenoeval package (pip install -e src/eval/stenoeval)") from e
    return spell_numbers(text)


def spell_numbers(words: list[Word]) -> list[Word]:
    out: list[Word] = []
    for w in words:
        if not any(c.isdigit() for c in w.text):
            out.append(w)
            continue
        parts = _spell(w.text).split()
        merged: list[str] = []
        for p in parts:
            if merged and not any(c.isalnum() for c in p):
                merged[-1] += p
            else:
                merged.append(p)
        if len(merged) <= 1:
            out.append(Word(merged[0], w.start_ms, w.end_ms, w.confidence) if merged else w)
            continue
        span = w.end_ms - w.start_ms
        for i, p in enumerate(merged):
            a = w.start_ms + span * i // len(merged)
            b = w.end_ms if i == len(merged) - 1 else w.start_ms + span * (i + 1) // len(merged)
            out.append(Word(p, a, max(b, a + 1), w.confidence))
    return out


# --- postprocess.strip_dashes (BACKLOG B39) -----------------------------------------------------------------------
import re  # noqa: E402

_DASH_AFTER_TERMINAL = re.compile("(?<=[.?!…])[—–]+")
_ANY_DASH = re.compile("[—–]+")


def strip_dashes_text(text: str, mode: str) -> str:
    """mode off | after_terminal | all.
    after_terminal: delete every run of em/en dashes (U+2014, U+2013) that directly follows . ? ! or the ellipsis char
                    ("Уточните.— Синий" -> "Уточните. Синий" at the word level: "Уточните.—" -> "Уточните.").
    all:            delete all em/en dashes (the hyphen-minus U+002D is never touched)."""
    if mode == "after_terminal":
        return _DASH_AFTER_TERMINAL.sub("", text)
    if mode == "all":
        return _ANY_DASH.sub("", text)
    return text


def strip_dashes(words: list[Word], mode: str) -> list[Word]:
    """Applied to ASR words (after spell_numbers, before the Aligner); a word that becomes empty is dropped."""
    if mode in (None, "", "off"):
        return words
    out = []
    for w in words:
        t = strip_dashes_text(w.text, mode)
        if t.strip():
            out.append(w if t == w.text else Word(t, w.start_ms, w.end_ms, w.confidence))
    return out
