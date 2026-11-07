"""Topic segmentation by embedding valleys (TextTiling style) over utterance vectors. Kotlin: `TopicSegmenter`.

The vectors are the same ones the search index stores, so segmentation costs no extra model run.
"""
from __future__ import annotations

import numpy as np


def gap_sims(vecs: np.ndarray, k: int = 3) -> np.ndarray:
    """sim[g] = cosine between the mean of the k utterances before gap g and the k after it (gap g is between g and g+1)."""
    n = len(vecs)
    out = np.ones(max(0, n - 1), dtype=np.float32)
    for g in range(n - 1):
        a = vecs[max(0, g - k + 1): g + 1].mean(0)
        b = vecs[g + 1: g + 1 + k].mean(0)
        out[g] = float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))
    return out


def boundaries(vecs: np.ndarray, chars: list[int], k: int = 3, alpha: float = 0.5, min_utts: int = 3, max_chars: int = 2400,
               min_chars: int = 300) -> list[int]:
    """Indices of utterances that start a new segment (excluding 0)."""
    n = len(vecs)
    if n < 2 * min_utts:
        return []
    s = gap_sims(vecs, k)
    thr = float(s.mean() - alpha * s.std())
    cand = [g for g in range(n - 1) if s[g] < thr and (g == 0 or s[g] <= s[g - 1]) and (g == n - 2 or s[g] <= s[g + 1])]
    cuts: list[int] = []
    last = 0
    for g in sorted(cand, key=lambda g: s[g]):          # strongest valleys first, keep the minimum segment size
        pos = g + 1
        pts = sorted(cuts + [pos])
        segs = zip([0] + pts, pts + [n])
        if all(b - a >= min_utts and sum(chars[a:b]) >= min_chars for a, b in segs):
            cuts.append(pos)
    cuts.sort()
    # enforce the maximum segment size (context budget): split at the weakest gap inside
    out, start = [], 0
    for end in cuts + [n]:
        out += _split_long(s, chars, start, end, max_chars)
        if end < n:
            out.append(end)
        start = end
    return sorted(set(out))


def _split_long(s, chars, a, b, max_chars):
    if sum(chars[a:b]) <= max_chars or b - a < 4:
        return []
    lo, hi = a + 1, b - 2
    g = min(range(lo, hi + 1), key=lambda g: s[g - 1] if g - 1 < len(s) else 1.0) if hi >= lo else (a + b) // 2
    return _split_long(s, chars, a, g, max_chars) + [g] + _split_long(s, chars, g, b, max_chars)


def lexical_vectors(texts: list[str]) -> np.ndarray:
    """TF-IDF over stems of one record (no model): a zero-cost alternative to utterance embeddings for the valley search."""
    import math
    from collections import Counter

    from .text import terms
    docs = [Counter(terms(t, mode="snow5")) for t in texts]
    df = Counter(w for d in docs for w in d)
    vocab = {w: i for i, w in enumerate(df)}
    n = len(texts)
    m = np.zeros((n, max(1, len(vocab))), dtype=np.float32)
    for i, d in enumerate(docs):
        for w, f in d.items():
            m[i, vocab[w]] = (1 + math.log(f)) * math.log(1 + n / df[w])
    return m
