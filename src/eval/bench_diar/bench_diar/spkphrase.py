"""Reference implementation of the "phrase-level speaker labelling" algorithm (Python == spec for Kotlin).

Pure numpy, deterministic, no scipy/sklearn: ward AHC is written out (Lance-Williams), silhouette is
explicit.  Inputs: ASR words (abs. ms), optional stock-diarizer turns (only used as speaker-CHANGE
POINTS), audio and an embedding callback (sherpa-onnx SpeakerEmbeddingExtractor).  Output: turns
(speaker, start_ms, end_ms) that replace the diarizer turns in front of the Aligner (ARCH sec. 5).

Steps (all integers = ms):
 1. units    = maximal runs of words with gaps <= gap_ms and span <= max_unit_ms;
               then each unit is cut between two consecutive words whose dominant diarizer turns differ,
               if both resulting parts last >= split_min_side_ms (turn dominant = max overlap with word).
 2. embed    = extractor(audio[unit.start - pad_ms, unit.end + pad_ms]) (unit longer than embed_max_s ->
               centre window); L2-normalise.
 3. reliable = units with duration >= min_reliable_ms (if < 3 such units: all units).
               If more than max_cluster_units, keep the longest ones.
 4. cluster  = ward AHC on the reliable (unit-length) vectors (Euclidean on the unit sphere).
 5. count    = k in [k_min, k_max] maximising the mean silhouette (cosine distance 1-<a,b>); ties -> smaller k.
 6. labels   = duration-weighted centroids of the k clusters (L2-normalised); EVERY unit gets the
               centroid with the largest dot product; `refine` rounds of re-estimating centroids from all units.
 7. turns    = (label, unit.start, unit.end).
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

SR = 16000


@dataclass(frozen=True)
class Params:
    gap_ms: int = 300
    max_unit_ms: int = 12000
    split_min_side_ms: int = 300
    pad_ms: int = 40
    embed_max_s: float = 10.0
    min_reliable_ms: int = 800
    k_min: int = 2
    k_max: int = 6
    max_cluster_units: int = 400
    refine: int = 1


# ---- 1. units -------------------------------------------------------------------------------
def make_units(words: list[tuple[int, int]], turns: list[tuple[int, int, int]] | None, p: Params) -> list[tuple[int, int, int, int]]:
    """words: [(start_ms, end_ms)], turns: [(speaker, start_ms, end_ms)].  Returns (start, end, w0, w1) with w1 exclusive."""
    out = []
    i, n = 0, len(words)
    while i < n:
        j = i + 1
        while j < n and words[j][0] - words[j - 1][1] <= p.gap_ms and words[j][1] - words[i][0] <= p.max_unit_ms:
            j += 1
        out.append((i, j))
        i = j
    if turns:
        res = []
        for i, j in out:
            dom = [_dominant(words[k], turns) for k in range(i, j)]
            cuts, start = [], 0
            for k in range(1, j - i):
                if dom[k] is not None and dom[k - 1] is not None and dom[k] != dom[k - 1]:
                    left = words[i + k - 1][1] - words[i + start][0]
                    right = words[j - 1][1] - words[i + k][0]
                    if left >= p.split_min_side_ms and right >= p.split_min_side_ms:
                        cuts.append(k)
                        start = k
            prev = 0
            for k in cuts + [j - i]:
                res.append((i + prev, i + k))
                prev = k
        out = res
    return [(words[i][0], words[j - 1][1], i, j) for i, j in out]


def _dominant(w, turns):
    best, bo = None, 0
    for spk, a, b in turns:                 # first turn wins ties (strictly greater overlap needed)
        o = min(w[1], b) - max(w[0], a)
        if o > bo:
            best, bo = spk, o
    return best


# ---- 2. embeddings ----------------------------------------------------------------------------
def embed_units(audio: np.ndarray, units, embed, p: Params) -> np.ndarray:
    E = []
    for a_ms, b_ms, _, _ in units:
        a = max(0, (a_ms - p.pad_ms) * SR // 1000)
        b = min(len(audio), (b_ms + p.pad_ms) * SR // 1000)
        if b - a > p.embed_max_s * SR:
            mid = (a + b) // 2
            a, b = mid - int(p.embed_max_s * SR) // 2, mid + int(p.embed_max_s * SR) // 2
        seg = audio[a:b]
        if len(seg) < int(0.3 * SR):
            seg = np.pad(seg, (0, int(0.3 * SR) - len(seg)))
        E.append(np.asarray(embed(seg), dtype=np.float64))
    E = np.stack(E) if E else np.zeros((0, 1))
    return E / (np.linalg.norm(E, axis=1, keepdims=True) + 1e-9)


# ---- 4. ward AHC --------------------------------------------------------------------------------
def ward_merges(Y: np.ndarray) -> list[tuple[int, int]]:
    """Lance-Williams ward on squared Euclidean distances (matrix form, O(n^3) but vectorised).
    Returns [(a, b)]: cluster ids (leaves 0..n-1, t-th merge creates id n+t).  Ties: smallest flat index (i<j)."""
    n = len(Y)
    D = ((Y[:, None, :] - Y[None, :, :]) ** 2).sum(-1)
    inf = np.inf
    D[np.tril_indices(n)] = inf                 # only i < j are candidates
    ids = list(range(n))                        # row slot -> cluster id
    size = np.ones(n)
    active = np.ones(n, dtype=bool)
    merges = []
    for t in range(n - 1):
        flat = int(np.argmin(D))
        i, j = divmod(flat, n)                  # slots, i < j
        dij = D[i, j]
        ni, nj = size[i], size[j]
        for m in np.where(active)[0]:
            if m == i or m == j:
                continue
            dim = D[min(i, m), max(i, m)]
            djm = D[min(j, m), max(j, m)]
            nm = size[m]
            D[min(i, m), max(i, m)] = ((ni + nm) * dim + (nj + nm) * djm - nm * dij) / (ni + nj + nm)
        D[j, :] = inf
        D[:, j] = inf
        active[j] = False
        size[i] = ni + nj
        merges.append((ids[i], ids[j]))
        ids[i] = n + t
    return merges


def cut(n: int, merges, k: int) -> np.ndarray:
    """Labels 0..k-1 (renumbered by first appearance) after n-k merges."""
    parent = list(range(2 * n - 1))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    for t in range(n - k):
        a, b = merges[t]
        parent[find(a)] = n + t
        parent[find(b)] = n + t
    roots = {}
    lab = np.zeros(n, dtype=int)
    for i in range(n):
        r = find(i)
        lab[i] = roots.setdefault(r, len(roots))
    return lab


def silhouette(Y: np.ndarray, lab: np.ndarray) -> float:
    ks = np.unique(lab)
    if len(ks) < 2:
        return -1.0
    D = 1.0 - Y @ Y.T
    s = []
    for i in range(len(Y)):
        same = lab == lab[i]
        if same.sum() <= 1:
            s.append(0.0)
            continue
        a = D[i, same].sum() / (same.sum() - 1)
        b = min(D[i, lab == q].mean() for q in ks if q != lab[i])
        s.append((b - a) / max(a, b, 1e-9))
    return float(np.mean(s))


# ---- 3-6. labelling -----------------------------------------------------------------------------
def label_units(E: np.ndarray, dur_ms: np.ndarray, p: Params) -> np.ndarray:
    n = len(E)
    if n == 0:
        return np.zeros(0, dtype=int)
    ok = dur_ms >= p.min_reliable_ms
    if ok.sum() < 3:
        ok = np.ones(n, dtype=bool)
    idx = np.where(ok)[0]
    if len(idx) > p.max_cluster_units:
        idx = np.sort(idx[np.argsort(-dur_ms[idx], kind="stable")[: p.max_cluster_units]])
    Y = E[idx]
    if len(idx) < 3:
        return np.zeros(n, dtype=int)
    merges = ward_merges(Y)
    best_k, best = max(p.k_min, 2), -2.0
    for k in range(max(p.k_min, 2), min(p.k_max, len(idx) - 1) + 1):
        s = silhouette(Y, cut(len(idx), merges, k))
        if s > best + 1e-12:
            best, best_k = s, k
    lab = cut(len(idx), merges, best_k)
    w = dur_ms.astype(float)

    def centroids(assign, sel):
        C = np.stack([(E[sel][assign == q] * w[sel][assign == q, None]).sum(0) for q in range(best_k)])
        return C / (np.linalg.norm(C, axis=1, keepdims=True) + 1e-9)
    C = centroids(lab, idx)
    out = np.argmax(E @ C.T, axis=1)
    for _ in range(p.refine):
        C = np.stack([(E[out == q] * w[out == q, None]).sum(0) if (out == q).any() else C[q] for q in range(best_k)])
        C = C / (np.linalg.norm(C, axis=1, keepdims=True) + 1e-9)
        out = np.argmax(E @ C.T, axis=1)
    return out


def phrase_turns(words, turns, audio, embed, p: Params = Params()):
    """words: [(start_ms,end_ms)]; turns: [(spk,start_ms,end_ms)] or None. Returns [(label, start_ms, end_ms)]."""
    units = make_units(words, turns, p)
    if not units:
        return []
    E = embed_units(audio, units, embed, p)
    dur = np.array([u[1] - u[0] for u in units])
    lab = label_units(E, dur, p)
    return [(int(l), u[0], u[1]) for u, l in zip(units, lab)]
