from __future__ import annotations

import math
from collections import Counter

import numpy as np

from .text import terms


class BM25:
    def __init__(self, docs: list[str], k1: float = 1.2, b: float = 0.75, mode: str = "snow5"):
        self.k1, self.b, self.mode = k1, b, mode
        self.tf = [Counter(terms(d, mode=mode)) for d in docs]
        self.dl = np.array([sum(c.values()) for c in self.tf], dtype=np.float32)
        self.avgdl = float(self.dl.mean()) if len(docs) else 1.0
        df: Counter = Counter()
        for c in self.tf:
            df.update(c.keys())
        n = len(docs)
        self.idf = {t: math.log(1 + (n - d + 0.5) / (d + 0.5)) for t, d in df.items()}
        self.inv: dict[str, list[int]] = {}
        for i, c in enumerate(self.tf):
            for t in c:
                self.inv.setdefault(t, []).append(i)
        self.n = n

    def scores(self, query: str) -> np.ndarray:
        s = np.zeros(self.n, dtype=np.float32)
        for t in set(terms(query, drop_query_words=True, mode=self.mode)):
            idf = self.idf.get(t)
            if idf is None:
                continue
            for i in self.inv[t]:
                f = self.tf[i][t]
                s[i] += idf * f * (self.k1 + 1) / (f + self.k1 * (1 - self.b + self.b * self.dl[i] / self.avgdl))
        return s
