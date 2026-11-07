"""Extract a speaker restriction from the question: `speaker_2`, «говорящий 2», «второй спикер», renamed names.

Returns the cleaned query (speaker spans removed) plus the set of speaker numbers and name stems.
Pure regex + stemmer -> Kotlin `SpeakerQueryParser`.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

from .text import stem_cached, tokens

_ROLE = r"(?:говорящ\w*|спикер\w*|собеседник\w*|участник\w*|голос\w*)"
_ORD = {"перв": 1, "втор": 2, "трет": 3, "четв": 4, "пят": 5, "шест": 6}
_WORDNUM = {"один": 1, "одного": 1, "два": 2, "двух": 2, "три": 3, "трех": 3, "четыре": 4, "пять": 5}
_PAT = [
    re.compile(r"speaker[_\s-]?(\d+)", re.I),
    re.compile(_ROLE + r"\s*(?:№|номер\w*|#)?\s*(\d+)", re.I),
    re.compile(_ROLE + r"\s*(?:номер\w*\s*)?(" + "|".join(_WORDNUM) + r")\b", re.I),
]
_ORD_PAT = re.compile(r"\b(" + "|".join(_ORD) + r")\w*\s+" + _ROLE, re.I)


@dataclass
class SpeakerQuery:
    clean: str
    numbers: set[int] = field(default_factory=set)
    name_stems: list[list[str]] = field(default_factory=list)   # each = stems of one matched display name

    @property
    def active(self) -> bool:
        return bool(self.numbers or self.name_stems)


def parse(query: str, display_names: set[str] | None = None) -> SpeakerQuery:
    q = query
    nums: set[int] = set()
    for p in _PAT:
        for m in p.finditer(q):
            g = m.group(1).lower().replace("ё", "е")
            nums.add(int(g) if g.isdigit() else _WORDNUM[g])
        q = p.sub(" ", q)
    for m in _ORD_PAT.finditer(q):
        nums.add(_ORD[m.group(1).lower()[:4] if m.group(1).lower()[:4] in _ORD else m.group(1).lower()])
    q = _ORD_PAT.sub(" ", q)
    names: list[list[str]] = []
    if display_names:
        qtok = tokens(q)
        qstems = [stem_cached(t) for t in qtok]
        drop = set()
        for nm in sorted(display_names, key=len, reverse=True):
            ns = [stem_cached(t) for t in tokens(nm)]
            if ns and ns[0] in qstems:
                names.append(ns)
                for i, s in enumerate(qstems):
                    if s in ns:
                        drop.add(qtok[i])
        if drop:
            q = " ".join(t for t in re.findall(r"\S+", q) if not any(d in t.lower().replace("ё", "е") for d in drop))
    return SpeakerQuery(re.sub(r"\s+", " ", q).strip(), nums, names)


def matches(sq: SpeakerQuery, speaker_id: str, record_names: dict[str, str]) -> bool:
    """Does an utterance of `speaker_id` (in a record with `record_names`) satisfy the restriction?"""
    if not sq.active:
        return True
    n = int(speaker_id.split("_")[1])
    if n in sq.numbers:
        return True
    nm = record_names.get(speaker_id)
    if nm:
        ns = [stem_cached(t) for t in tokens(nm)]
        return any(ns and ns[0] == m[0] for m in sq.name_stems)
    return False
