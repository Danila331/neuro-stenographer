"""Extractive fallback digest without an LLM (lite tier): same fragments (valley cuts), same output structure, text = verbatim utterances.

title = most characteristic stems of the fragment; points = per speaker the most informative utterance; decisions/tasks = utterances with cue words.
"""
from __future__ import annotations

import math
import re
from collections import Counter

from . import summary as S
from .data import Record
from .text import terms, tokens

DECISION = re.compile(r"\b(решил\w*|решени\w*|договорил\w*|принят\w*|утвержд\w*|итого|согласен|согласны|единогласно|назнач\w*|фиксируем|записываем|значит)\b", re.I)
TASK = re.compile(r"\b(нужно|надо|должен\w*|подготовлю|составлю|сделаю|займусь|проверю|закажу|пришлю|напишу|свяжусь|до (?:пятниц|понедельник|вторник|сред|четверг|суббот|воскресенья|конца)\w*|к (?:пятниц|завтра)\w*|завтра)\b", re.I)


def digest(rec: Record, vecs=None, target_chars: int = 1500, **kw) -> list[S.Topic]:
    frags = S.plan(rec, vecs, target_chars, **kw)
    df = Counter(w for u in rec.utts for w in set(terms(u.text, mode="snow5")))
    n = len(rec.utts)
    out = []
    for lo, hi in frags:
        tf = Counter(w for i in range(lo, hi) for w in terms(rec.utts[i].text, mode="snow5"))
        top = [w for w, _ in sorted(tf.items(), key=lambda x: -x[1] * math.log(1 + n / df[x[0]]))[:3]]
        # title from the original word forms of the top stems
        forms = {}
        for i in range(lo, hi):
            for t in tokens(rec.utts[i].text):
                forms.setdefault(S.terms(t, mode="snow5")[0] if S.terms(t, mode="snow5") else "", t)
        t = S.Topic(" ".join(forms.get(w, w) for w in top).capitalize() or "Тема", lo, hi - 1)

        def info(i):
            ts = terms(rec.utts[i].text, mode="snow5")
            return sum(math.log(1 + n / df[w]) for w in set(ts))
        for spk in sorted({rec.utts[i].spk for i in range(lo, hi)}):
            ids = [i for i in range(lo, hi) if rec.utts[i].spk == spk and len(rec.utts[i].text.split()) >= 3]
            if ids:
                b = max(ids, key=info)
                t.points.append(S.Point(spk, rec.utts[b].text, [b]))
        for i in range(lo, hi):
            tx = rec.utts[i].text
            if len(tx.split()) < 3:
                continue
            if DECISION.search(tx):
                t.decisions.append(S.Item(tx, [i]))
            elif TASK.search(tx):
                t.tasks.append(S.Item(tx, [i], f"Говорящий {rec.utts[i].spk}"))
        t.decisions, t.tasks = t.decisions[:3], t.tasks[:3]
        out.append(t)
    return S.merge(out, 0.6)
