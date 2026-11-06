"""Post-processing on top of sherpa-onnx diarization (prototype; every step is Kotlin-portable).

Pipeline "recluster":
  1. sherpa pass with a LOW clustering threshold -> over-segmented but locally pure turns.
  2. embed every turn (long turns are cut into <= WIN s windows) with the same embedding model.
  3. speaker-count estimate (eigengap of normalised Laplacian / AHC gap) + clustering of reliable units.
  4. short units (< MIN_EMB s) are assigned to the nearest centroid.
  5. optional: drop tiny clusters, merge adjacent same-speaker turns.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from scipy.cluster.hierarchy import fcluster, linkage
from scipy.spatial.distance import pdist

from .metrics import Segments

SR = 16000


class Embedder:
    def __init__(self, model_path: str, threads: int = 2):
        import sherpa_onnx as so
        self.ex = so.SpeakerEmbeddingExtractor(so.SpeakerEmbeddingExtractorConfig(model_path, num_threads=threads))

    def __call__(self, x: np.ndarray) -> np.ndarray:
        s = self.ex.create_stream()
        s.accept_waveform(SR, x.astype(np.float32))
        s.input_finished()
        v = np.asarray(self.ex.compute(s), dtype=np.float32)
        return v / (np.linalg.norm(v) + 1e-9)


@dataclass
class Unit:
    a: float
    b: float
    emb: np.ndarray | None = None
    label: int = -1


def make_units(segs: Segments, win: float = 3.0, min_len: float = 0.0) -> list[Unit]:
    """Turns (any label) -> windows. Long turns are split into equal non-overlapping windows of ~`win` s."""
    units = []
    for _, a, b in sorted(segs, key=lambda s: (s[1], s[2])):
        if b - a < min_len:
            continue
        if b - a <= win * 1.25:
            units.append(Unit(a, b))
        else:
            n = max(2, int(round((b - a) / win)))
            step = (b - a) / n          # equal, NON-overlapping windows (each owns its time span)
            units += [Unit(a + i * step, a + (i + 1) * step) for i in range(n)]
    return units


def embed_units(emb: Embedder, x: np.ndarray, units: list[Unit]) -> None:
    for u in units:
        u.emb = emb(x[int(u.a * SR): int(u.b * SR)])


# ---------- speaker-count estimation ----------

def eigengap_k(E: np.ndarray, kmax: int = 8, p_frac: float = 0.3, min_gap_ratio: float = 0.0) -> int:
    """NME-SC style: prune affinity to top-p neighbours, normalised-Laplacian eigengap.  Returns k >= 1."""
    n = len(E)
    if n < 3:
        return 1
    A = np.clip(E @ E.T, 0, 1)
    best_k, best_g = 1, 1e9
    for p in sorted({max(2, int(n * p_frac * f)) for f in (0.5, 0.75, 1.0)} | {min(n - 1, 6)}):
        p = min(p, n - 1)
        M = np.zeros_like(A)
        idx = np.argsort(-A, axis=1)[:, :p]
        rows = np.arange(n)[:, None]
        M[rows, idx] = A[rows, idx]
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


def ahc_labels(E: np.ndarray, k: int | None = None, dist_thr: float | None = None, method="average") -> np.ndarray:
    if len(E) == 1:
        return np.zeros(1, dtype=int)
    Z = linkage(pdist(E, "cosine"), method=method)
    if k is not None:
        lab = fcluster(Z, k, "maxclust")
    else:
        lab = fcluster(Z, dist_thr, "distance")
    return lab - 1


def spectral_labels(E: np.ndarray, k: int, seed: int = 0) -> np.ndarray:
    from sklearn.cluster import KMeans
    n = len(E)
    if k <= 1 or n <= k:
        return np.arange(n) if n <= k else np.zeros(n, dtype=int)
    A = np.clip(E @ E.T, 0, 1)
    p = max(2, min(n - 1, int(n * 0.3)))
    M = np.zeros_like(A)
    idx = np.argsort(-A, axis=1)[:, :p]
    rows = np.arange(n)[:, None]
    M[rows, idx] = A[rows, idx]
    M = 0.5 * (M + M.T)
    d = M.sum(1) + 1e-9
    L = np.eye(n) - M / np.sqrt(d[:, None] * d[None, :])
    w, V = np.linalg.eigh(L)
    U = V[:, :k]
    U = U / (np.linalg.norm(U, axis=1, keepdims=True) + 1e-9)
    return KMeans(k, n_init=10, random_state=seed).fit_predict(U)


# ---------- main algorithm ----------

@dataclass
class ReclusterParams:
    win: float = 3.0
    min_emb: float = 0.8          # units shorter than this do not take part in clustering
    count: str = "eigengap"       # eigengap | ahc_thr | fixed
    ahc_thr: float = 0.5          # cosine-distance threshold for count="ahc_thr"
    kmax: int = 8
    method: str = "spectral"      # spectral | ahc
    min_cluster_s: float = 0.0    # clusters with less speech than this are merged into nearest
    num_speakers: int | None = None


def recluster(x: np.ndarray, base: Segments, emb: Embedder, P: ReclusterParams) -> Segments:
    units = make_units(base, P.win)
    if not units:
        return []
    embed_units(emb, x, units)
    long_idx = [i for i, u in enumerate(units) if u.b - u.a >= P.min_emb]
    if not long_idx:
        long_idx = list(range(len(units)))
    E = np.stack([units[i].emb for i in long_idx])
    # weight longer units equally; clustering on windows
    if P.num_speakers:
        k = P.num_speakers
    elif P.count == "eigengap":
        k = eigengap_k(E, P.kmax)
    elif P.count == "ahc_thr":
        k = None
    else:
        raise ValueError(P.count)
    if k is None:
        lab = ahc_labels(E, dist_thr=P.ahc_thr)
    elif P.method == "spectral":
        lab = spectral_labels(E, k)
    else:
        lab = ahc_labels(E, k=k)
    labels = np.unique(lab)
    cents = np.stack([E[lab == l].mean(0) for l in labels])
    cents /= np.linalg.norm(cents, axis=1, keepdims=True) + 1e-9
    # drop tiny clusters
    if P.min_cluster_s > 0 and len(labels) > 1:
        dur = np.array([sum(units[long_idx[i]].b - units[long_idx[i]].a for i in np.where(lab == l)[0]) for l in labels])
        keep = dur >= P.min_cluster_s
        if keep.any() and not keep.all():
            cents = cents[keep]
            labels = labels[keep]
    for u in units:
        u.label = int(np.argmax(cents @ u.emb))
    return merge_units(units)


def merge_units(units: list[Unit], max_gap: float = 0.0) -> Segments:
    """Windows of one turn can overlap by `hop`; resolve: later window owns the overlap start midpoint."""
    out: Segments = []
    for u in sorted(units, key=lambda u: (u.a, u.b)):
        out.append((f"spk{u.label}", u.a, u.b))
    # merge consecutive same-label segments (overlapping or touching)
    res: Segments = []
    last: dict[str, int] = {}
    for s, a, b in out:
        if s in last and a - res[last[s]][2] <= max_gap + 1e-6:
            s0, a0, b0 = res[last[s]]
            res[last[s]] = (s0, a0, max(b0, b))
        else:
            res.append((s, a, b))
            last[s] = len(res) - 1
    return res
