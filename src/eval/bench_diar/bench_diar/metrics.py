"""DER / JER on 10 ms frames, overlap-aware (NIST md-eval style).

Segments are lists of (speaker, start_s, end_s). Overlapping segments are allowed on both sides.
collar: seconds excluded on EACH side of every reference boundary (NIST/dscore ``-c 0.25`` means
+-0.25 s).  collar=0 scores everything.  Cross-checked against pyannote.metrics (collar=0) in
tests/test_metrics.py.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from scipy.optimize import linear_sum_assignment

FRAME = 0.01

Segments = list[tuple[str, float, float]]


def _matrix(segs: Segments, n_frames: int) -> tuple[np.ndarray, list[str]]:
    spk = sorted({s for s, _, _ in segs})
    idx = {s: i for i, s in enumerate(spk)}
    m = np.zeros((n_frames, max(len(spk), 1)), dtype=bool)
    for s, a, b in segs:
        i0 = max(0, int(round(a / FRAME)))
        i1 = min(n_frames, int(round(b / FRAME)))
        if i1 > i0:
            m[i0:i1, idx[s]] = True
    return m, spk


def _collar_mask(ref: Segments, n_frames: int, collar: float) -> np.ndarray:
    """True for frames that are scored."""
    keep = np.ones(n_frames, dtype=bool)
    if collar <= 0:
        return keep
    c = int(round(collar / FRAME))
    for _, a, b in ref:
        for t in (a, b):
            k = int(round(t / FRAME))
            keep[max(0, k - c): min(n_frames, k + c)] = False
    return keep


@dataclass
class Score:
    der: float
    miss: float
    fa: float
    conf: float
    jer: float
    total: float  # scored reference speech, s
    n_ref: int
    n_hyp: int


def score(ref: Segments, hyp: Segments, duration: float, collar: float = 0.0) -> Score:
    n = int(np.ceil(duration / FRAME)) + 1
    R, rs = _matrix(ref, n)
    H, hs = _matrix(hyp, n)
    if not ref:
        R[:] = False
    if not hyp:
        H[:] = False
    keep = _collar_mask(ref, n, collar)
    Rk, Hk = R[keep], H[keep]
    nr, nh = Rk.sum(1), Hk.sum(1)
    total = nr.sum()
    # co-occurrence matrix (frames) for optimal 1-1 mapping
    co = Rk.T.astype(np.float32) @ Hk.astype(np.float32)
    if co.size:
        ri, hi = linear_sum_assignment(-co)
        correct = co[ri, hi].sum()
    else:
        correct = 0.0
    miss = np.maximum(nr - nh, 0).sum()
    fa = np.maximum(nh - nr, 0).sum()
    conf = np.minimum(nr, nh).sum() - correct
    d = max(total, 1e-9)
    # JER (per reference speaker Jaccard, optimal mapping), on collar-masked frames
    jer = 0.0
    n_ref_spk = len(rs) if ref else 0
    if n_ref_spk:
        inter = co
        rsz = Rk.sum(0).astype(np.float32)[:, None]
        hsz = Hk.sum(0).astype(np.float32)[None, :]
        union = rsz + hsz - inter
        jac = np.where(union > 0, inter / np.maximum(union, 1e-9), 0.0)
        ri, hi = linear_sum_assignment(-jac)
        matched = np.zeros(n_ref_spk)
        matched[ri] = jac[ri, hi]
        jer = float(np.mean(1.0 - matched))
    return Score(
        der=float((miss + fa + conf) / d) if ref else (0.0 if not hyp else 1.0),
        miss=float(miss / d), fa=float(fa / d), conf=float(conf / d), jer=jer,
        total=float(total) * FRAME, n_ref=n_ref_spk, n_hyp=len(hs) if hyp else 0,
    )


def aggregate(scores: list[Score]) -> dict:
    """Duration-weighted DER (as in pyannote/NIST over a corpus) + mean JER + speaker-count accuracy."""
    tot = sum(s.total for s in scores) or 1e-9
    out = {
        "der": sum(s.der * s.total for s in scores) / tot,
        "miss": sum(s.miss * s.total for s in scores) / tot,
        "fa": sum(s.fa * s.total for s in scores) / tot,
        "conf": sum(s.conf * s.total for s in scores) / tot,
        "jer": float(np.mean([s.jer for s in scores])) if scores else float("nan"),
        "n": len(scores),
        "spk_acc": float(np.mean([s.n_ref == s.n_hyp for s in scores])) if scores else float("nan"),
        "spk_mae": float(np.mean([abs(s.n_ref - s.n_hyp) for s in scores])) if scores else float("nan"),
    }
    return out
