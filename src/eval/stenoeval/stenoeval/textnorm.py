"""Text normalisation modes for WER.

The jury's exact normalisation is unknown, so every metric is computed in several modes:

``raw``      tokens = text.split(); nothing else is touched (case, punctuation, ё all matter).
``norm``     NFKC -> lower -> ё->е -> drop stress marks -> every char that is not a letter/digit
             becomes a space (so punctuation and *all* dashes/hyphens are separators) -> split.
             Hyphens inside words are therefore SPLIT: "кто-то" -> "кто", "то".
             Rationale: symmetric for hypothesis and reference, the usual choice of ASR
             normalisers (Whisper's basic normaliser does the same), and it removes the
             "кто-то" vs "кто то" ambiguity that would otherwise cost 2 errors.
``norm_hy``  like ``norm`` but a hyphen *between two letters/digits* is kept inside the token
             ("кто-то" stays one word) - the opposite hyphen policy, for comparison.
``punct``    punctuation and case are part of the text (the jury apparently counts punctuation, see
             docs/team/BACKLOG.md Q13/Q14; е/ё is NOT counted): NFKC -> ё->е, Ё->Е -> split on
             whitespace. Marks stay glued to words, as in ``raw`` ("день!" is one token).
``punct_sep`` like ``punct`` but every punctuation mark (Unicode category P*) becomes its own
             token: "день!" -> "день", "!"; «да» -> "«", "да", "»"; "..." -> three "." tokens
             (NFKC turns "…" into "..."). Kept inside the word: a hyphen/dash between two
             letters/digits ("кто-то") and a "," or "." between two digits ("3,5").
``punct_lc`` like ``punct_sep`` in lower case.
``norm_num`` ``norm`` + numbers written with digits are spelled out in Russian words
             (num2words, lang=ru) BEFORE punctuation removal: "25" -> "двадцать пять",
             "3,5" -> "три запятая пять", "5-й" -> "пятый".
             Limitation: case/gender/number cannot be guessed ("25 котам" -> "двадцать пять
             котам" is fine, but "в 1990 году" -> "в тысяча девятьсот девяносто году" while a
             human would write "тысяча девятьсот девяностом"); numbers are always
             nominative, ordinals are masculine nominative, "%" and currency signs are
             treated as punctuation. Digit strings longer than 18 digits (phone numbers)
             are read digit by digit.
"""

from __future__ import annotations

import re
import unicodedata
from functools import lru_cache

MODES = ("raw", "norm", "norm_hy", "norm_num", "punct", "punct_sep", "punct_lc")

_NUM_RE = re.compile(r"(?<!\w)(\d+)(?:[.,](\d+))?(?:(-?)([а-яё]{1,4}))?(?!\w)", re.I)
# suffixes that mark an ordinal number ("5-й", "21-го"); anything else ("2-х", "10-ти") is read as a cardinal
_ORD_SUFFIXES = frozenset(
    "й ый ий ой я ая яя ое ее е ые ие го ого его му ому ему м ом ем ым им ых их ю ую юю ей ой".split()
)


def _spell_digits(s: str) -> str:
    from num2words import num2words

    return " ".join(num2words(int(c), lang="ru") for c in s)


@lru_cache(maxsize=4096)
def _cardinal(n: str) -> str:
    from num2words import num2words

    n = n.lstrip("0") or "0"
    if len(n) > 18:
        return _spell_digits(n)
    out = num2words(int(n), lang="ru")
    # spoken Russian says "тысяча девятьсот...", not "одна тысяча девятьсот..."
    return out[len("одна "):] if out.startswith("одна тысяча") else out


@lru_cache(maxsize=1024)
def _ordinal(n: str) -> str:
    from num2words import num2words

    n = n.lstrip("0") or "0"
    if len(n) > 18:
        return _spell_digits(n)
    try:
        return num2words(int(n), lang="ru", to="ordinal")
    except Exception:  # num2words gaps -> fall back to cardinal
        return _cardinal(n)


def _num_repl(m: re.Match[str]) -> str:
    whole, frac, hy, suffix = m.group(1), m.group(2), m.group(3), (m.group(4) or "").lower()
    if hy and suffix not in _ORD_SUFFIXES and len(suffix) <= 2:
        suffix = ""  # case ending of a cardinal ("2-х", "10-ти"): the number words stay nominative
    if frac:
        # decimal fraction: "3,5" -> "три запятая пять"
        frac_words = _spell_digits(frac) if frac.startswith("0") else _cardinal(frac)
        return f" {_cardinal(whole)} запятая {frac_words} {suffix} "
    if suffix in _ORD_SUFFIXES:
        return f" {_ordinal(whole)} "
    return f" {_cardinal(whole)} {suffix} "


def spell_numbers(text: str) -> str:
    """Replace digit sequences with Russian words (see module docstring for limits)."""
    return _NUM_RE.sub(_num_repl, text)


def _basic(text: str, keep_hyphen: bool = False) -> str:
    t = unicodedata.normalize("NFKC", text).lower()
    t = t.replace("ё", "е")
    out: list[str] = []
    n = len(t)
    for i, ch in enumerate(t):
        cat = unicodedata.category(ch)
        if ch == "́":  # stress mark
            continue
        if cat[0] in "LN" or cat == "Mn" or cat == "Mc":
            out.append(ch)
        elif (
            keep_hyphen
            and cat == "Pd"
            and 0 < i < n - 1
            and t[i - 1].isalnum()
            and t[i + 1].isalnum()
        ):
            out.append("-")
        else:
            out.append(" ")
    return "".join(out)


def _yo(t: str) -> str:
    return t.replace("ё", "е").replace("Ё", "Е")


def _punct_sep(text: str, lower: bool) -> list[str]:
    t = _yo(unicodedata.normalize("NFKC", text))
    if lower:
        t = t.lower()
    out: list[str] = []
    cur: list[str] = []
    n = len(t)
    for i, ch in enumerate(t):
        if ch.isspace():
            if cur:
                out.append("".join(cur))
                cur = []
            continue
        if unicodedata.category(ch)[0] == "P":
            prev_c = t[i - 1] if i > 0 else ""
            next_c = t[i + 1] if i < n - 1 else ""
            inside = (
                (unicodedata.category(ch) == "Pd" and prev_c.isalnum() and next_c.isalnum())
                or (ch in ",." and prev_c.isdigit() and next_c.isdigit())
            )
            if not inside:
                if cur:
                    out.append("".join(cur))
                    cur = []
                out.append(ch)
                continue
        cur.append(ch)
    if cur:
        out.append("".join(cur))
    return out


def tokens(text: str, mode: str) -> list[str]:
    if mode == "raw":
        return text.split()
    if mode == "punct":
        return _yo(unicodedata.normalize("NFKC", text)).split()
    if mode == "punct_sep":
        return _punct_sep(text, lower=False)
    if mode == "punct_lc":
        return _punct_sep(text, lower=True)
    if mode == "norm":
        return _basic(text).split()
    if mode == "norm_hy":
        return _basic(text, keep_hyphen=True).split()
    if mode == "norm_num":
        return _basic(spell_numbers(unicodedata.normalize("NFKC", text))).split()
    raise ValueError(f"unknown normalisation mode {mode!r}; choose from {MODES}")


def normalize(text: str, mode: str = "norm") -> str:
    return " ".join(tokens(text, mode))
