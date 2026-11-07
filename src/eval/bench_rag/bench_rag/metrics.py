from __future__ import annotations

import numpy as np

from .evalset import Question


def score(q: Question, hits) -> dict:
    """Chunk-level: a chunk is relevant if it contains any gold utterance. recall@5 = share of gold utterances covered by the top 5."""
    rel = [any((h.chunk.record, u) in q.gold for u in h.chunk.utts) for h in hits]
    first = next((i for i, r in enumerate(rel) if r), None)
    covered = {(h.chunk.record, u) for h in hits[:5] for u in h.chunk.utts} & q.gold
    return {"hit1": float(rel[:1] == [True]), "hit5": float(any(rel[:5])), "recall5": len(covered) / max(1, len(q.gold)),
            "mrr": 1 / (first + 1) if first is not None and first < 10 else 0.0}


def agg(rows: list[dict]) -> dict:
    if not rows:
        return {}
    return {k: round(float(np.mean([r[k] for r in rows])), 3) for k in rows[0]}
