"""Aligner: words x speaker turns -> utterances.  ARCHITECTURE §5 -- reference for the Kotlin port.

Pure and deterministic: integers only, no floats, no randomness, no I/O.  Every detail that is not
pinned by §5 is pinned here and covered by `tests/align_cases.json` (the Android tests replay the
same file), so the Kotlin port must reproduce it 1:1:

* Input words are stably sorted by (start_ms, end_ms).  Turns are stably sorted by
  (start_ms, end_ms, speaker); that order is the tie-break order everywhere below.
* Step 1 (speaker per word).  overlap(w, t) = max(0, min(w.end, t.end) - max(w.start, t.start)).
  The word gets the speaker of the turn with the STRICTLY largest overlap (first one in turn order
  wins ties).  If every overlap is 0, the nearest turn by distance from the word midpoint
  (mid = (start+end)//2) to the turn interval wins (0 inside; first in turn order wins ties).
  No turns at all -> speaker 0.  own(w) = overlap_with_chosen_turn (ms; 0 for the nearest-turn fallback).
* Step 2 (islands).  The word list is split into maximal runs of equal speaker (using the speakers
  from step 1).  A run is an island iff all hold: it has a previous AND a next run, those two have
  the same speaker, len(run) <= min_island_words, last.end - first.start < min_island_ms, and every
  word of the run has 2*own(w) < max(1, w.end - w.start) (own overlap < 50 %, strict, integers).  Islands are decided once, on the original runs
  (no iteration), and take the speaker of their neighbours.
* Step 3 (utterances).  Consecutive words are merged while the speaker is unchanged and
  word.start - prev_word.end <= max_gap_ms; otherwise a new utterance starts.
* Step 4.  start = first word start, end = last word end (end >= start + 1 is enforced).
* Step 5.  Utterances are sorted by (start_ms, end_ms) (stable) and speakers are renumbered
  1..N by first appearance in that order; speakers without words simply do not appear.
* Step 6.  text = words joined with a single space.
"""
from __future__ import annotations

from dataclasses import dataclass

from .types import SpeakerTurn, Utterance, Word


@dataclass(frozen=True)
class AlignParams:
    max_gap_ms: int = 1500
    min_island_words: int = 1
    min_island_ms: int = 400

    @staticmethod
    def from_config(cfg: dict) -> "AlignParams":
        a = cfg.get("align", {}) or {}
        d = AlignParams()
        return AlignParams(
            int(a.get("max_gap_ms", d.max_gap_ms)),
            int(a.get("min_island_words", d.min_island_words)),
            int(a.get("min_island_ms", d.min_island_ms)),
        )


def _overlap(ws: int, we: int, ts: int, te: int) -> int:
    return max(0, min(we, te) - max(ws, ts))


def assign_speakers(words: list[Word], turns: list[SpeakerTurn]) -> tuple[list[int], list[int]]:
    """Step 1. Returns (speaker per word, own overlap in ms per word)."""
    turns = sorted(turns, key=lambda t: (t.start_ms, t.end_ms, t.speaker))
    speakers: list[int] = []
    owns: list[int] = []
    for w in words:
        if not turns:
            speakers.append(0)
            owns.append(0)
            continue
        best_i, best_ov = -1, 0
        for i, t in enumerate(turns):
            ov = _overlap(w.start_ms, w.end_ms, t.start_ms, t.end_ms)
            if ov > best_ov:
                best_i, best_ov = i, ov
        if best_i < 0:
            mid = (w.start_ms + w.end_ms) // 2
            best_d = None
            for i, t in enumerate(turns):
                d = 0 if t.start_ms <= mid <= t.end_ms else min(abs(mid - t.start_ms), abs(mid - t.end_ms))
                if best_d is None or d < best_d:
                    best_i, best_d = i, d
        speakers.append(turns[best_i].speaker)
        owns.append(best_ov)
    return speakers, owns


def smooth_islands(words: list[Word], speakers: list[int], owns: list[int], p: AlignParams) -> list[int]:
    """Step 2."""
    out = list(speakers)
    n = len(words)
    runs: list[tuple[int, int]] = []  # [i, j) half-open
    i = 0
    while i < n:
        j = i
        while j < n and speakers[j] == speakers[i]:
            j += 1
        runs.append((i, j))
        i = j
    for k in range(1, len(runs) - 1):
        a, b = runs[k]
        prev_sp, next_sp = speakers[runs[k - 1][0]], speakers[runs[k + 1][0]]
        if prev_sp != next_sp:
            continue
        if b - a > p.min_island_words:
            continue
        if words[b - 1].end_ms - words[a].start_ms >= p.min_island_ms:
            continue
        if any(2 * owns[x] >= max(1, words[x].end_ms - words[x].start_ms) for x in range(a, b)):
            continue
        for x in range(a, b):
            out[x] = prev_sp
    return out


def align(words: list[Word], turns: list[SpeakerTurn], params: AlignParams | None = None) -> list[Utterance]:
    p = params or AlignParams()
    words = sorted(words, key=lambda w: (w.start_ms, w.end_ms))  # stable
    if not words:
        return []
    speakers, owns = assign_speakers(words, turns)
    speakers = smooth_islands(words, speakers, owns, p)

    groups: list[tuple[int, list[Word]]] = []
    for w, sp in zip(words, speakers):
        if groups and groups[-1][0] == sp and w.start_ms - groups[-1][1][-1].end_ms <= p.max_gap_ms:
            groups[-1][1].append(w)
        else:
            groups.append((sp, [w]))

    utts = []
    for sp, ws in groups:
        start = ws[0].start_ms
        end = max(ws[-1].end_ms, start + 1)
        utts.append((sp, start, end, " ".join(w.text for w in ws), ws))
    utts.sort(key=lambda u: (u[1], u[2]))  # stable

    ids: dict[int, int] = {}
    result = []
    for sp, start, end, text, ws in utts:
        n = ids.setdefault(sp, len(ids) + 1)
        result.append(Utterance(n, start, end, text, list(ws)))
    return result
