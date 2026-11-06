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
