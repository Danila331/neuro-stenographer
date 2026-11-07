"""ASR-phrase level speaker labelling (prototype for 'reassign speakers at the ASR-utterance level').

Units = runs of consecutive ASR words separated by pauses > gap_ms (optionally also split where the
pyannote turn label changes).  One embedding per unit (exact audio of the unit, no overlap
subtraction), clustering of the units, label -> turn over the unit span -> Aligner as usual.
Everything is plain arithmetic over a few hundred vectors (Kotlin-portable); needs only the
SpeakerEmbeddingExtractor of sherpa-onnx, ASR words and (optionally) the stock diarizer turns.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass

import numpy as np

from refpipe.types import SpeakerTurn, Word

from .pipeline import STAGE_CACHE
from .runner import emb_path

SR = 16000


@dataclass
class Unit:
    a: int          # start ms
    b: int          # end ms
    w0: int         # first word index
    w1: int         # last word index (exclusive)


def make_units(words: list[Word], gap_ms: int = 300, max_ms: int = 12000, split_turns: list[SpeakerTurn] | None = None,
               min_side_ms: int = 600) -> list[Unit]:
    units: list[Unit] = []
    i, n = 0, len(words)
    while i < n:
        j = i + 1
        while j < n and words[j].start_ms - words[j - 1].end_ms <= gap_ms and words[j].end_ms - words[i].start_ms <= max_ms:
            j += 1
        units.append(Unit(words[i].start_ms, words[j - 1].end_ms, i, j))
        i = j
    if split_turns:
        units = [u2 for u in units for u2 in _split_by_turns(u, words, split_turns, min_side_ms)]
    return units


def _dominant(w: Word, turns: list[SpeakerTurn]):
    best, bo = None, 0
    for t in turns:
        o = min(w.end_ms, t.end_ms) - max(w.start_ms, t.start_ms)
        if o > bo:
            best, bo = t.speaker, o
    return best


def _split_by_turns(u: Unit, words, turns, min_side_ms) -> list[Unit]:
    labs = [_dominant(words[k], turns) for k in range(u.w0, u.w1)]
    cuts = []
    start = 0
    for k in range(1, len(labs)):
        if labs[k] is not None and labs[k - 1] is not None and labs[k] != labs[k - 1]:
            left = words[u.w0 + k - 1].end_ms - words[u.w0 + start].start_ms
            right = words[u.w1 - 1].end_ms - words[u.w0 + k].start_ms
            if left >= min_side_ms and right >= min_side_ms:
                cuts.append(k)
                start = k
    if not cuts:
        return [u]
    out, prev = [], 0
    for k in cuts + [u.w1 - u.w0]:
        out.append(Unit(words[u.w0 + prev].start_ms, words[u.w0 + k - 1].end_ms, u.w0 + prev, u.w0 + k))
        prev = k
    return out


class Extractor:
    def __init__(self, emb: str, threads: int = 1):
        import sherpa_onnx as so
        self.emb = emb
        self.ex = so.SpeakerEmbeddingExtractor(so.SpeakerEmbeddingExtractorConfig(str(emb_path(emb)), num_threads=threads))

    def __call__(self, x: np.ndarray) -> np.ndarray:
        s = self.ex.create_stream()
        s.accept_waveform(SR, x.astype(np.float32))
        s.input_finished()
        return np.asarray(self.ex.compute(s), dtype=np.float32)


def embed_units(key: str, audio: np.ndarray, units: list[Unit], ex: Extractor, pad_ms: int = 40,
                max_len_s: float = 10.0) -> np.ndarray:
    """Embedding per unit; units longer than max_len_s are embedded from the centre window."""
    h = hashlib.md5(f"{key}|{ex.emb}|{pad_ms}|{max_len_s}|" .encode() + str([(u.a, u.b) for u in units]).encode()).hexdigest()[:16]
    f = STAGE_CACHE / f"U_{h}.npy"
    if f.exists():
        return np.load(f)
    out = []
    for u in units:
        a = max(0, int((u.a - pad_ms) * SR / 1000))
        b = min(len(audio), int((u.b + pad_ms) * SR / 1000))
        if b - a > max_len_s * SR:
            mid = (a + b) // 2
            a, b = mid - int(max_len_s * SR / 2), mid + int(max_len_s * SR / 2)
        seg = audio[a:b]
        if len(seg) < int(0.3 * SR):
            seg = np.pad(seg, (0, int(0.3 * SR) - len(seg)))
        out.append(ex(seg))
    E = np.stack(out) if out else np.zeros((0, 1), dtype=np.float32)
    STAGE_CACHE.mkdir(parents=True, exist_ok=True)
    np.save(f, E)
    return E


def _norm(X):
    return X / (np.linalg.norm(X, axis=1, keepdims=True) + 1e-9)


def label_units(E: np.ndarray, durs_ms: np.ndarray, thr: float = 0.5, method: str = "average", meansub: bool = False,
                min_emb_ms: int = 800, max_speakers: int = 8, k: int | None = None, count: str = "thr") -> np.ndarray:
    """Cluster reliable units (>= min_emb_ms) by AHC on cosine distance; the short ones -> nearest centroid."""
    from scipy.cluster.hierarchy import fcluster, linkage
    from scipy.spatial.distance import pdist

    n = len(E)
    if n == 0:
        return np.zeros(0, dtype=int)
    X = _norm(E)
    ok = durs_ms >= min_emb_ms
    if ok.sum() < 2:
        ok = np.ones(n, dtype=bool)
    mu = np.average(X[ok], axis=0, weights=durs_ms[ok]) if meansub else 0.0
    Y = _norm(X - mu) if meansub else X
    Yr = Y[ok]
    if len(Yr) < 2:
        return np.zeros(n, dtype=int)
    Z = linkage(pdist(Yr, "cosine"), method)
    if k:
        lab = fcluster(Z, k, "maxclust")
    elif count == "eigengap":
        from .variants import eigengap_k
        lab = fcluster(Z, eigengap_k(Yr, max_speakers), "maxclust")
    else:
        lab = fcluster(Z, thr, "distance")
        if lab.max() > max_speakers:
            lab = fcluster(Z, max_speakers, "maxclust")
    ks = np.unique(lab)
    w = durs_ms[ok].astype(float)
    C = np.stack([(Yr[lab == q] * w[lab == q, None]).sum(0) for q in ks])
    C = _norm(C)
    out = np.argmax(Y @ C.T, axis=1)
    out[np.where(ok)[0]] = np.searchsorted(ks, lab)
    return out


def units_to_turns(units: list[Unit], labels: np.ndarray) -> list[SpeakerTurn]:
    return [SpeakerTurn(int(l), u.a, u.b) for u, l in zip(units, labels)]
