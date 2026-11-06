"""Clustering / post-processing variants operating on a pipeline.Prepared object.

All of them are plain linear algebra on a few hundred d-dim vectors -> trivial to port to Kotlin.
Contract: f(P, **params) -> cluster label per (chunk, speaker) pair (np.ndarray[int], len == len(P.pairs)).
"""
from __future__ import annotations

import numpy as np
from scipy.cluster.hierarchy import fcluster, linkage
from scipy.spatial.distance import pdist


def pair_frames(P) -> np.ndarray:
    """Non-overlapped active frames per (chunk, speaker) pair (what the embedding was computed from)."""
    L = P.labels * (P.labels.sum(-1, keepdims=True) < 2)
    return np.array([int(L[i, :, j].sum()) for i, j in P.pairs])


def _norm(E):
    return E / (np.linalg.norm(E, axis=1, keepdims=True) + 1e-9)


def assign_nearest(E, cents):
    return np.argmax(_norm(E) @ _norm(cents).T, axis=1)


def centroids(E, lab, w=None):
    w = np.ones(len(E)) if w is None else w
    En = _norm(E)
    ks = np.unique(lab)
    return ks, np.stack([(En[lab == k] * w[lab == k, None]).sum(0) for k in ks])


def ahc(E, thr=None, k=None, method="average"):
    if len(E) < 2:
        return np.zeros(len(E), dtype=int)
    Z = linkage(pdist(_norm(E), "cosine"), method)
    lab = fcluster(Z, k, "maxclust") if k else fcluster(Z, thr, "distance")
    return lab - 1


def eigengap_k(E, kmax=8, p_frac=0.3):
    n = len(E)
    if n < 3:
        return 1
    A = np.clip(_norm(E) @ _norm(E).T, 0, 1)
    best_k, best_g = 1, 1e9
    for p in sorted({max(2, min(n - 1, int(n * p_frac * f))) for f in (0.5, 0.75, 1.0)}):
        M = np.zeros_like(A)
        idx = np.argsort(-A, axis=1)[:, :p]
        r = np.arange(n)[:, None]
        M[r, idx] = A[r, idx]
        M = 0.5 * (M + M.T)
        d = M.sum(1) + 1e-9
        L = np.eye(n) - M / np.sqrt(d[:, None] * d[None, :])
        w = np.sort(np.linalg.eigvalsh(L))
        kk = min(kmax, n - 1)
        gaps = np.diff(w[: kk + 1])
        k = int(np.argmax(gaps)) + 1
        g = (p / n) / (gaps[k - 1] + 1e-9)
        if g < best_g:
            best_g, best_k = g, k
    return best_k


def cluster_robust(P, thr=0.6, min_frames=40, method="average", k=None, merge_cos=None, min_cluster_frames=0,
                   count="thr"):
    """Cluster only reliable embeddings (>= min_frames clean frames); the rest -> nearest centroid.

    count: "thr" (AHC cosine-distance threshold), "eigengap" (spectral-style count estimate then AHC with k),
           "fixed" (k given).
    merge_cos: after clustering, merge clusters whose centroids have cosine similarity >= merge_cos.
    min_cluster_frames: clusters with fewer total clean frames are dissolved into the nearest big cluster.
    """
    E = P.embs
    if len(E) == 0:
        return np.zeros(0, dtype=int)
    fr = pair_frames(P)
    ok = fr >= min_frames
    if ok.sum() < 2:
        ok = np.ones(len(E), dtype=bool)
    Er = E[ok]
    if count == "eigengap":
        lab_r = ahc(Er, k=eigengap_k(Er), method=method)
    elif count == "fixed":
        lab_r = ahc(Er, k=k, method=method)
    else:
        lab_r = ahc(Er, thr=thr, method=method)
    ks, C = centroids(Er, lab_r, fr[ok].astype(float))
    if min_cluster_frames and len(ks) > 1:
        tot = np.array([fr[ok][lab_r == q].sum() for q in ks])
        keep = tot >= min_cluster_frames
        if keep.any() and not keep.all():
            C = C[keep]
    if merge_cos is not None and len(C) > 1:
        Cn = _norm(C)
        while len(Cn) > 1:
            S = Cn @ Cn.T - 2 * np.eye(len(Cn))
            i, j = np.unravel_index(np.argmax(S), S.shape)
            if S[i, j] < merge_cos:
                break
            C[i] = C[i] + C[j]
            C = np.delete(C, j, 0)
            Cn = _norm(C)
    return assign_nearest(E, C)
