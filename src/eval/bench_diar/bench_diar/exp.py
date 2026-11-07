"""Helpers for e2e experiments on cached pipeline stages."""
from __future__ import annotations

import numpy as np

from refpipe.align import AlignParams
from refpipe.types import SpeakerTurn

from .data import load_audio
from .e2e import Case, score_case, summarize
from .pipeline import PCfg, Prepared, assemble, cluster_fast


def load_preps(cases: list[Case], cfg: PCfg) -> dict[str, Prepared]:
    out = {}
    for c in cases:
        out[c.key] = Prepared(c.key.replace("/", "__"), load_audio(c.audio), cfg, keep_audio=False)
    return out


def to_turns(segs) -> list[SpeakerTurn]:
    return [SpeakerTurn(int(s[3:]), int(round(a * 1000)), int(round(b * 1000))) for s, a, b in segs]


def stock_labels(P: Prepared, threshold: float, max_speakers: int = 8, num_speakers: int = -1):
    cl = cluster_fast(P.embs, threshold, num_speakers)
    if num_speakers <= 0 and max_speakers and len(cl) and cl.max() + 1 > max_speakers:
        cl = cluster_fast(P.embs, threshold, max_speakers)
    return cl


def run(cases, preps, labels_fn, min_on=0.2, min_off=0.3, params=AlignParams(), with_cpwer=False, turns_fn=None):
    """labels_fn(case, P) -> cluster label per pair.  Returns (summary, rows)."""
    rows = []
    for c in cases:
        P = preps[c.key]
        if turns_fn is not None:
            turns = turns_fn(c, P)
        else:
            cl = labels_fn(c, P)
            segs = assemble(P.labels, P.sh, P.sm, P.n_samples, P.pairs, cl, min_on, min_off) if len(P.pairs) else []
            turns = to_turns(segs)
        rows.append(score_case(c, turns, params, with_cpwer))
    return summarize(rows), rows
