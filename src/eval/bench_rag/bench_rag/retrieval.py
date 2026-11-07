"""Hybrid retrieval: BM25 (Russian stemming) + dense vectors + speaker filter, fused with RRF or a weighted sum."""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from . import speaker_filter as sf
from .bm25 import BM25
from .chunker import Chunk
from .data import Record
from .embed import Embedder


@dataclass
class Hit:
    chunk: Chunk
    score: float


class Index:
    def __init__(self, chunks: list[Chunk], recs: dict[str, Record], vectors: np.ndarray | None = None, stem_mode: str = "snow"):
        self.chunks, self.recs = chunks, recs
        self.vecs = vectors
        self.bm25 = BM25([c.text for c in chunks], mode=stem_mode)
        self.rec_of = np.array([c.record for c in chunks])
        self.all_names = {nm for r in recs.values() for nm in r.names.values()}

    def mask(self, record: str | None, sq: sf.SpeakerQuery, speaker_mode: str) -> np.ndarray:
        m = np.ones(len(self.chunks), dtype=bool)
        if record:
            m &= self.rec_of == record
        if speaker_mode == "hard" and sq.active:
            sm = np.array([any(sf.matches(sq, s, self.recs[c.record].names) for s in c.speakers) for c in self.chunks])
            if (m & sm).any():
                m &= sm        # empty result (diarisation disagrees with the question) -> keep the unfiltered list
        return m

    def search(self, query: str, qvec_fn=None, k: int = 5, record: str | None = None, fusion: str = "rrf", alpha: float = 0.5,
               speaker_mode: str = "hard", bm25: bool = True, dense: bool = True, rrf_k: int = 60, soft_boost: float = 0.15) -> list[Hit]:
        scope_names = self.recs[record].names.values() if record else self.all_names
        sq = sf.parse(query, set(scope_names)) if speaker_mode != "off" else sf.SpeakerQuery(query)
        q = sq.clean or query
        m = self.mask(record, sq, speaker_mode)
        idx = np.flatnonzero(m)
        sc_b = self.bm25.scores(q)[idx] if bm25 else None
        sc_d = (self.vecs[idx] @ qvec_fn(q)) if (dense and self.vecs is not None) else None
        if sc_b is not None and sc_d is not None:
            if fusion == "rrf":
                s = np.zeros(len(idx), dtype=np.float32)
                for sc, w in ((sc_b, 1 - alpha), (sc_d, alpha)):
                    order = np.argsort(-sc, kind="stable")
                    r = np.empty(len(idx), dtype=np.float32)
                    r[order] = np.arange(len(idx))
                    pos = sc > 0 if sc is sc_b else np.ones(len(idx), dtype=bool)   # zero BM25 = no evidence
                    s += np.where(pos, 2 * w / (rrf_k + r + 1), 0)
            else:
                def mm(x):
                    lo, hi = float(x.min()), float(x.max())
                    return (x - lo) / (hi - lo) if hi > lo else np.zeros_like(x)
                s = alpha * mm(sc_d) + (1 - alpha) * mm(sc_b)
        else:
            s = sc_d if sc_d is not None else sc_b
        if speaker_mode == "soft" and sq.active:
            sm = np.array([any(sf.matches(sq, sp, self.recs[self.chunks[i].record].names) for sp in self.chunks[i].speakers) for i in idx])
            s = s + soft_boost * sm * (float(np.max(s)) - float(np.min(s)) + 1e-6)
        order = np.argsort(-s, kind="stable")[:k]
        return [Hit(self.chunks[idx[i]], float(s[i])) for i in order]
