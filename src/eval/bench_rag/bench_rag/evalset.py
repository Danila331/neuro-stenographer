from __future__ import annotations

from dataclasses import dataclass

from .data import Record, load_record, map_gold
from .questions import NAMES, Q

HISTORY_IDS = [f"c{i:02d}" for i in range(1, 41)]


@dataclass
class Question:
    id: str
    kind: str
    record: str | None
    text: str
    gold: set[tuple[str, int]]       # (record id, utterance idx) in the chosen corpus
    speaker: str | None               # true speaker for speaker questions (reference labels)


def load_corpus(kind: str) -> dict[str, Record]:
    recs = {rid: load_record(rid, kind) for rid in HISTORY_IDS}
    for rid, nm in NAMES.items():
        recs[rid].names = dict(nm)
    return recs


def build_questions(kind: str = "ref") -> tuple[dict[str, Record], list[Question]]:
    """kind=ref: clean reference text and labels. kind=asr: real pipeline output, gold mapped by time overlap."""
    ref = {rid: load_record(rid, "ref") for rid in HISTORY_IDS}
    corpus = load_corpus(kind)
    out = []
    for qid, k, rec, text, gold in Q:
        gset, spk = set(), None
        for rid, sub in gold:
            hits = [u.idx for u in ref[rid].utts if sub in u.text]
            if len(hits) != 1:
                raise ValueError(f"{qid}: gold {sub!r} in {rid} matches {len(hits)} reference utterances")
            if k in ("speaker",) and spk is None:
                spk = ref[rid].utts[hits[0]].speaker
            idx = hits if kind == "ref" else map_gold(ref[rid], corpus[rid], hits)
            gset |= {(rid, i) for i in idx}
        if "{spk}" in text:
            text = text.replace("{spk}", spk.split("_")[1])
        out.append(Question(qid, k, rec, text, gset, spk))
    return corpus, out
