"""Timestamp accuracy of utterance boundaries via word alignment.

Algorithm
---------
1. Reference words: every reference utterance is tokenised (mode ``norm_num``, so "25" and
   "двадцать пять" are comparable); each token remembers its utterance index.
2. Hypothesis words: the hypothesis format only has utterance-level times, so every utterance's
   [start, end] interval is split among its tokens proportionally to token length in characters
   (word i gets ``len_i / sum(len)`` of the interval, no pauses modelled). If the caller has real
   word times (``HypWord`` list) they are used directly.
3. Both token sequences (utterances ordered by start_ms, end_ms) are aligned globally with
   Levenshtein.
4. For each reference utterance, the hypothesis words aligned to its words are collected:
   exact matches ("C") if there is at least one, otherwise substituted words ("S").
   Predicted start = start of the earliest collected hypothesis word,
   predicted end   = end of the latest one.
   Reference utterances with no aligned hypothesis word are "unmatched" (not in the MAE).
5. MAE_start / MAE_end = mean |predicted - reference| (ms) over matched reference utterances;
   ``start_acc300`` = share of *all* reference utterances (unmatched count as failures) with
   |Δstart| < 300 ms; same for end.

Hypothesis word times: if ``<id>.full.json`` (extended export with per-word times) lies next to
the hypothesis, its words are used (``ts=words`` in the notes of results.csv). Both the original
``utterances[].words`` shape and the app export's ``transcript[].words`` shape are supported;
otherwise the proportional split above is used (``ts=utt``).

Because only aligned words are used, a deleted first word of an utterance shifts the predicted
start to the next recognised word; this error is part of the metric (it measures what a user
would see when comparing timestamps) and is small for good transcripts.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Sequence

from .textnorm import tokens
from .transcript import Utt, sort_utts
from .wer import align

ALIGN_MODE = "norm_num"
ACC_THRESHOLD_MS = 300


@dataclass(frozen=True)
class HypWord:
    text: str
    start_ms: float
    end_ms: float


@dataclass
class TimingResult:
    n_ref_utts: int
    n_matched: int
    start_mae_ms: float | None
    end_mae_ms: float | None
    start_median_ms: float | None
    end_median_ms: float | None
    start_acc300: float | None   # over all reference utterances
    end_acc300: float | None
    start_abs_errors: list[float] = field(default_factory=list)
    end_abs_errors: list[float] = field(default_factory=list)


def hyp_words_from_utts(utts: Sequence[Utt], mode: str = ALIGN_MODE) -> list[HypWord]:
    words: list[HypWord] = []
    for u in sort_utts(list(utts)):
        toks = tokens(u.text, mode)
        if not toks:
            continue
        total = sum(len(t) for t in toks)
        span = max(u.end_ms - u.start_ms, 0)
        pos = float(u.start_ms)
        for t in toks:
            w = span * len(t) / total
            words.append(HypWord(t, pos, pos + w))
            pos += w
    return words


def hyp_words_from_full(obj: object, mode: str = ALIGN_MODE) -> list[HypWord] | None:
    """Real word times from an extended export (`<id>.full.json`, docs/team/ARCHITECTURE_TEAM.md section 1).

    Accepts either ``{"utterances":[...]}`` or the app export
    ``{"transcript":[...]}``, where each item has ``start_ms``, ``end_ms``, ``text`` and
    optionally ``words:[{"text","start_ms","end_ms"}]``. Utterances are ordered by
    (start_ms, end_ms), words keep their order. A word that normalises to several tokens (e.g.
    "25" in norm_num) shares its interval proportionally to token length. An utterance without a
    usable ``words`` list falls back to proportional splitting of its own interval. Returns None
    if the structure is unusable or yields no words at all.
    """
    utts = None
    if isinstance(obj, dict):
        for key in ("utterances", "transcript"):
            candidate = obj.get(key)
            if isinstance(candidate, list) and candidate:
                utts = candidate
                break
    if not isinstance(utts, list):
        return None

    def num(v: object) -> float | None:
        return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) and v == v else None

    items: list[tuple[float, float, dict]] = []
    for u in utts:
        if isinstance(u, dict):
            s, e = num(u.get("start_ms")), num(u.get("end_ms"))
            items.append((s if s is not None else 0.0, e if e is not None else 0.0, u))
    items.sort(key=lambda x: (x[0], x[1]))
    out: list[HypWord] = []
    for s, e, u in items:
        words = u.get("words")
        got: list[HypWord] = []
        if isinstance(words, list):
            for w in words:
                if not isinstance(w, dict) or not isinstance(w.get("text"), str):
                    continue
                ws, we = num(w.get("start_ms")), num(w.get("end_ms"))
                if ws is None or we is None:
                    continue
                toks = tokens(w["text"], mode)
                total = sum(len(t) for t in toks)
                pos = ws
                for t in toks:
                    d = (we - ws) * len(t) / total
                    got.append(HypWord(t, pos, pos + d))
                    pos += d
        if not got and isinstance(u.get("text"), str):
            got = hyp_words_from_utts([Utt("", int(s), int(e), u["text"])], mode)
        out.extend(got)
    return out or None


def _median(xs: list[float]) -> float | None:
    if not xs:
        return None
    s = sorted(xs)
    n = len(s)
    return float(s[n // 2]) if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2.0


def timing(
    ref: Sequence[Utt],
    hyp: Sequence[Utt],
    hyp_words: Sequence[HypWord] | None = None,
) -> TimingResult:
    ref_sorted = sort_utts(list(ref))
    ref_tokens: list[str] = []
    owner: list[int] = []
    for k, u in enumerate(ref_sorted):
        for t in tokens(u.text, ALIGN_MODE):
            ref_tokens.append(t)
            owner.append(k)
    hw = list(hyp_words) if hyp_words is not None else hyp_words_from_utts(hyp)
    ops = align(ref_tokens, [w.text for w in hw])

    exact: dict[int, list[int]] = {}
    subs: dict[int, list[int]] = {}
    for op, i, j in ops:
        if i is None or j is None:
            continue
        if op == "C":
            exact.setdefault(owner[i], []).append(j)
        elif op == "S":
            subs.setdefault(owner[i], []).append(j)

    se: list[float] = []
    ee: list[float] = []
    for k, u in enumerate(ref_sorted):
        js = exact.get(k) or subs.get(k)
        if not js:
            continue
        ps = min(hw[j].start_ms for j in js)
        pe = max(hw[j].end_ms for j in js)
        se.append(abs(ps - u.start_ms))
        ee.append(abs(pe - u.end_ms))

    n = len(ref_sorted)
    m = len(se)
    return TimingResult(
        n_ref_utts=n,
        n_matched=m,
        start_mae_ms=sum(se) / m if m else None,
        end_mae_ms=sum(ee) / m if m else None,
        start_median_ms=_median(se),
        end_median_ms=_median(ee),
        start_acc300=(sum(x < ACC_THRESHOLD_MS for x in se) / n) if n else None,
        end_acc300=(sum(x < ACC_THRESHOLD_MS for x in ee) / n) if n else None,
        start_abs_errors=se,
        end_abs_errors=ee,
    )
