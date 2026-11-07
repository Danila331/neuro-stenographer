"""WER / cpWER on top of a plain Levenshtein alignment."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

import numpy as np
from scipy.optimize import linear_sum_assignment

from .textnorm import tokens
from .transcript import Utt, sort_utts


@dataclass(frozen=True)
class WerCounts:
    ref_words: int
    sub: int
    dele: int
    ins: int

    @property
    def errors(self) -> int:
        return self.sub + self.dele + self.ins

    @property
    def wer(self) -> float:
        """errors / ref_words; with an empty reference the denominator is 1 (each inserted word = 1.0)."""
        return self.errors / max(self.ref_words, 1)

    def __add__(self, o: "WerCounts") -> "WerCounts":
        return WerCounts(self.ref_words + o.ref_words, self.sub + o.sub, self.dele + o.dele, self.ins + o.ins)


ZERO = WerCounts(0, 0, 0, 0)


try:  # C++ implementation: ~100x faster than the pure-Python DP below, identical distances
    from rapidfuzz.distance import Levenshtein as _Lev
except ImportError:  # pragma: no cover
    _Lev = None


def edit_distance(ref: Sequence[str], hyp: Sequence[str]) -> int:
    """Levenshtein distance (unit costs)."""
    if _Lev is not None:
        return int(_Lev.distance(list(ref), list(hyp)))
    return _edit_distance_py(ref, hyp)


def _edit_distance_py(ref: Sequence[str], hyp: Sequence[str]) -> int:
    """Pure-Python two-row DP (reference implementation, used when rapidfuzz is missing)."""
    n, m = len(ref), len(hyp)
    if n == 0:
        return m
    if m == 0:
        return n
    prev = list(range(m + 1))
    for i in range(1, n + 1):
        cur = [i] + [0] * m
        r = ref[i - 1]
        for j in range(1, m + 1):
            c = prev[j - 1] + (r != hyp[j - 1])
            d = prev[j] + 1
            if d < c:
                c = d
            d = cur[j - 1] + 1
            if d < c:
                c = d
            cur[j] = c
        prev = cur
    return prev[m]


def align(ref: Sequence[str], hyp: Sequence[str]) -> list[tuple[str, int | None, int | None]]:
    """Global Levenshtein alignment.

    Returns a list of ``(op, i, j)`` with op in {"C" correct, "S" substitution, "D" deletion,
    "I" insertion}; ``i`` indexes ``ref`` (None for I), ``j`` indexes ``hyp`` (None for D).
    The number of errors is always the Levenshtein distance; which of several equally good
    alignments is returned is deterministic but implementation-defined.
    """
    if _Lev is not None:
        return _align_rf(list(ref), list(hyp))
    return _align_py(ref, hyp)


def _align_rf(ref: list[str], hyp: list[str]) -> list[tuple[str, int | None, int | None]]:
    ops: list[tuple[str, int | None, int | None]] = []
    i = j = 0
    for e in _Lev.editops(ref, hyp):
        while i < e.src_pos:
            ops.append(("C", i, j))
            i += 1
            j += 1
        if e.tag == "replace":
            ops.append(("S", i, j))
            i += 1
            j += 1
        elif e.tag == "delete":
            ops.append(("D", i, None))
            i += 1
        else:
            ops.append(("I", None, j))
            j += 1
    while i < len(ref):
        ops.append(("C", i, j))
        i += 1
        j += 1
    return ops


def _align_py(ref: Sequence[str], hyp: Sequence[str]) -> list[tuple[str, int | None, int | None]]:
    """Pure-Python DP alignment (tie-breaking: diagonal > deletion > insertion)."""
    n, m = len(ref), len(hyp)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        d[i][0] = i
    d[0] = list(range(m + 1))
    for i in range(1, n + 1):
        di, dp, r = d[i], d[i - 1], ref[i - 1]
        for j in range(1, m + 1):
            c = dp[j - 1] + (r != hyp[j - 1])
            x = dp[j] + 1
            if x < c:
                c = x
            x = di[j - 1] + 1
            if x < c:
                c = x
            di[j] = c
    ops: list[tuple[str, int | None, int | None]] = []
    i, j = n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0 and d[i][j] == d[i - 1][j - 1] + (ref[i - 1] != hyp[j - 1]):
            ops.append(("C" if ref[i - 1] == hyp[j - 1] else "S", i - 1, j - 1))
            i, j = i - 1, j - 1
        elif i > 0 and d[i][j] == d[i - 1][j] + 1:
            ops.append(("D", i - 1, None))
            i -= 1
        else:
            ops.append(("I", None, j - 1))
            j -= 1
    ops.reverse()
    return ops


def count_ops(ops: Sequence[tuple[str, int | None, int | None]]) -> WerCounts:
    s = sum(o == "S" for o, _, _ in ops)
    dl = sum(o == "D" for o, _, _ in ops)
    ins = sum(o == "I" for o, _, _ in ops)
    c = sum(o == "C" for o, _, _ in ops)
    return WerCounts(c + s + dl, s, dl, ins)


def wer_tokens(ref: Sequence[str], hyp: Sequence[str]) -> WerCounts:
    return count_ops(align(ref, hyp))


def concat_tokens(utts: Sequence[Utt], mode: str) -> list[str]:
    """Words of all utterances in start_ms order (ties: end_ms, then file order)."""
    out: list[str] = []
    for u in sort_utts(list(utts)):
        out.extend(tokens(u.text, mode))
    return out


def wer(ref: Sequence[Utt], hyp: Sequence[Utt], mode: str) -> WerCounts:
    """Speaker-agnostic WER: utterances concatenated in start_ms order."""
    return wer_tokens(concat_tokens(ref, mode), concat_tokens(hyp, mode))


def _by_speaker(utts: Sequence[Utt], mode: str) -> dict[str, list[str]]:
    d: dict[str, list[str]] = {}
    for u in sort_utts(list(utts)):
        d.setdefault(u.speaker_id, []).extend(tokens(u.text, mode))
    return d


@dataclass(frozen=True)
class CpWer:
    counts: WerCounts
    mapping: dict[str, str | None]  # reference speaker -> hypothesis speaker (None = unmatched)

    @property
    def wer(self) -> float:
        return self.counts.wer


def cpwer(ref: Sequence[Utt], hyp: Sequence[Utt], mode: str) -> CpWer:
    """Concatenated minimum-permutation WER.

    Each speaker's utterances are concatenated in start_ms order. Reference speakers are
    assigned to distinct hypothesis speakers (Hungarian algorithm) so that the total number of
    word errors is minimal. Unassigned reference speakers count all their words as deletions,
    unassigned hypothesis speakers count all their words as insertions. Same definition as
    MeetEval's cpWER.
    """
    r = _by_speaker(ref, mode)
    h = _by_speaker(hyp, mode)
    rs, hs = list(r), list(h)
    n = max(len(rs), len(hs))
    if n == 0:
        return CpWer(ZERO, {})
    cost = np.zeros((n, n), dtype=np.int64)
    for i in range(n):
        for j in range(n):
            a = r[rs[i]] if i < len(rs) else []
            b = h[hs[j]] if j < len(hs) else []
            cost[i, j] = edit_distance(a, b)
    ri, ci = linear_sum_assignment(cost)
    total = ZERO
    mapping: dict[str, str | None] = {}
    for i, j in zip(ri, ci):
        a = r[rs[i]] if i < len(rs) else []
        b = h[hs[j]] if j < len(hs) else []
        total = total + wer_tokens(a, b)
        if i < len(rs):
            mapping[rs[i]] = hs[j] if j < len(hs) else None
    return CpWer(total, mapping)
