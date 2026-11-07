"""Speaker-count estimation + clustering of unit embeddings (all Kotlin-portable: AHC + simple scores)."""
from __future__ import annotations

import numpy as np
from scipy.cluster.hierarchy import fcluster, linkage
from scipy.spatial.distance import pdist


def _norm(X):
    return X / (np.linalg.norm(X, axis=1, keepdims=True) + 1e-9)


def build_Z(Y: np.ndarray, method: str):
    """Y: L2-normalised rows.  ward -> Euclidean on the unit sphere; others -> cosine distance."""
    if method == "ward":
        return linkage(Y, "ward")
    return linkage(pdist(Y, "cosine"), method)


def k_jump(Z, kmax=8, min_k=1, rel=True) -> int:
    """Largest gap between consecutive merge heights (dendrogram jump). Heights of the last merges:
    Z[-1] merges to 1 cluster, Z[-j] separates j clusters from j-1... choose k with biggest h[k-1]-h[k]."""
    h = Z[:, 2]
    n = len(h) + 1
    best_k, best = min_k, -1.0
    for k in range(max(min_k, 1), min(kmax, n - 1) + 1):
        # cutting to k clusters: heights above cut = h[-k..]; gap = h[-k] - h[-k-1]
        top = h[-k]                      # merge that would join k -> k-1 clusters
        below = h[-k - 1] if n - 1 - k - 1 >= 0 else 0.0
        g = (top - below) / (below + 1e-9) if rel else (top - below)
        if g > best:
            best, best_k = g, k
    return best_k


def silhouette(Y, lab) -> float:
    ks = np.unique(lab)
    if len(ks) < 2:
        return -1.0
    D = 1 - Y @ Y.T
    s = []
    for i in range(len(Y)):
        same = lab == lab[i]
        if same.sum() > 1:
            a = D[i, same].sum() / (same.sum() - 1)
        else:
            s.append(0.0)
            continue
        b = min(D[i, lab == q].mean() for q in ks if q != lab[i])
        s.append((b - a) / max(a, b, 1e-9))
    return float(np.mean(s))


def k_silhouette(Y, Z, kmax=6, min_k=1, k1_thr=0.0, tol=0.0, up_tol=0.0) -> int:
    best_k, best = 1, k1_thr
    scores = {}
    for k in range(2, min(kmax, len(Y) - 1) + 1):
        scores[k] = silhouette(Y, fcluster(Z, k, "maxclust"))
        if scores[k] > best:
            best, best_k = scores[k], k
    if tol > 0 and best_k > 2:     # parsimony: smallest k within `tol` of the best silhouette
        for k in sorted(scores):
            if scores[k] >= best - tol:
                best_k = k
                break
    if up_tol > 0:                 # prefer MORE speakers: largest k within `up_tol` of the best silhouette
        for k in sorted(scores, reverse=True):
            if scores[k] >= best - up_tol:
                best_k = max(best_k, k)
                break
    return max(best_k, min_k)


def cluster_units(E: np.ndarray, w: np.ndarray, *, method="ward", count="jump", thr=None, k=None, kmax=6,
                  min_k=1, k1_thr=0.0, rel=True, min_emb: np.ndarray | None = None, tol=0.0, refine=0, up_tol=0.0):
    """E: unit embeddings, w: unit weights (ms), min_emb: bool mask of reliable units (default all).
    Returns labels (len(E)) with short units assigned to the nearest centroid."""
    n = len(E)
    X = _norm(E)
    ok = np.ones(n, bool) if min_emb is None else min_emb.copy()
    if ok.sum() < 3:
        ok[:] = True
    Y = X[ok]
    if len(Y) < 2:
        return np.zeros(n, dtype=int), 1
    Z = build_Z(Y, method)
    if k is not None:
        kk = k
    elif count == "jump":
        kk = k_jump(Z, kmax, min_k, rel)
    elif count == "sil":
        kk = k_silhouette(Y, Z, kmax, min_k, k1_thr, tol, up_tol)
    elif count == "thr":
        lab = fcluster(Z, thr, "distance")
        kk = int(min(lab.max(), kmax))
    else:
        raise ValueError(count)
    kk = int(max(1, min(kk, len(Y))))
    lab = fcluster(Z, kk, "maxclust") - 1
    ks = np.unique(lab)
    ww = w[ok].astype(float)
    C = _norm(np.stack([(Y[lab == q] * ww[lab == q, None]).sum(0) for q in ks]))
    out = np.argmax(X @ C.T, axis=1)
    out[np.where(ok)[0]] = np.searchsorted(ks, lab)
    for _ in range(refine):          # k-means style polishing with duration-weighted centroids
        ww_all = w.astype(float)
        C = _norm(np.stack([(X[out == q] * ww_all[out == q, None]).sum(0) if (out == q).any() else C[q]
                            for q in range(len(ks))]))
        out = np.argmax(X @ C.T, axis=1)
    return out, len(ks)
