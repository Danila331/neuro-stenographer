"""Chunking of a transcript for the search index (B51). Pure logic -> Kotlin `Chunker`."""
from __future__ import annotations

from dataclasses import dataclass

from .data import Record, Utt, fmt_time


@dataclass
class Chunk:
    cid: int
    record: str
    utts: list[int]                 # utterance indices inside the record (consecutive)
    speakers: tuple[str, ...]       # speaker ids of the utterances (same order)
    start_ms: int
    end_ms: int
    text: str                       # plain text (BM25)
    embed_text: str                 # text for the dense model (with speaker labels for windows)

    @property
    def label(self) -> str:
        return f"[{fmt_time(self.start_ms)}, Говорящий {self.speakers[0].split('_')[1]}]"


def merge_turns(utts: list[Utt], max_gap_ms: int = 1500, max_chars: int = 400) -> list[list[Utt]]:
    """Glue consecutive fragments of one speaker (the pipeline splits sentences at numbers, etc.)."""
    groups: list[list[Utt]] = []
    for u in utts:
        if groups and groups[-1][-1].speaker == u.speaker and u.start_ms - groups[-1][-1].end_ms <= max_gap_ms \
                and sum(len(x.text) + 1 for x in groups[-1]) + len(u.text) <= max_chars:
            groups[-1].append(u)
        else:
            groups.append([u])
    return groups


def chunk_record(rec: Record, mode: str = "utt", start_cid: int = 0) -> list[Chunk]:
    """mode: utt | turn | win<W>[s<S>]  (windows are built over turns; W turns, stride S, default W//2)."""
    base = [[u] for u in rec.utts] if mode == "utt" else merge_turns(rec.utts)
    units = base
    if mode.startswith("win"):
        w, _, s = mode[3:].partition("s")
        w = int(w)
        stride = int(s) if s else max(1, w // 2)
        units = []
        i = 0
        while True:
            g = base[i:i + w]
            if not g:
                break
            units.append([u for grp in g for u in grp])
            if i + w >= len(base):
                break
            i += stride
    out = []
    for k, grp in enumerate(units):
        text = " ".join(u.text for u in grp)
        multi = len({u.speaker for u in grp}) > 1 or mode.startswith("win")
        emb = "\n".join(f"Говорящий {u.spk}: {u.text}" for u in grp) if multi else text
        out.append(Chunk(start_cid + k, rec.id, [u.idx for u in grp], tuple(u.speaker for u in grp),
                         grp[0].start_ms, grp[-1].end_ms, text, emb))
    return out


def chunk_corpus(recs: dict[str, Record], mode: str) -> list[Chunk]:
    out: list[Chunk] = []
    for r in recs.values():
        out += chunk_record(r, mode, len(out))
    return out
