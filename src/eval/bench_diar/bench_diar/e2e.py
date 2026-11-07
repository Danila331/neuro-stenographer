"""End-to-end diarization scoring (BACKLOG B22): words (cached ASR from a refpipe run) x turns -> Aligner
-> final utterances -> stenoeval DER / JER / #speakers / cpWER.  Only the turns change between experiments.
"""
from __future__ import annotations

import csv
import json
from dataclasses import dataclass, field
from pathlib import Path

from refpipe.align import AlignParams, align
from refpipe.types import SpeakerTurn, Word
from stenoeval import diar as sdiar
from stenoeval import wer as swer
from stenoeval.transcript import Utt

from .data import CACHE

RUNS = CACHE / "runs"
DATASETS = ["ru-dialog-synth", "ru-dialog-tts-cats", "en-dialog-synth", "ru-en-mixed"]


@dataclass
class Case:
    key: str                   # dataset/id
    dataset: str
    id: str
    audio: Path
    split: str
    condition: str
    duration_ms: int
    nspk: int
    ref: list[Utt]
    words: list[Word]
    base_turns: list[SpeakerTurn] = field(default_factory=list)


def load_cases(datasets=None, split="dev", base_run="refpipe-medium-rescue") -> list[Case]:
    out = []
    for ds in datasets or DATASETS:
        root = CACHE / "data" / ds
        for r in csv.DictReader(open(root / "manifest.csv", encoding="utf-8")):
            if split != "all" and r["split"] != split:
                continue
            fj = RUNS / base_run / f"{r['id']}.full.json"
            if not fj.exists():
                continue
            full = json.loads(fj.read_text(encoding="utf-8"))
            words = [Word(w["text"], w["start_ms"], w["end_ms"], w.get("confidence"))
                     for u in full["utterances"] for w in u["words"]]
            words.sort(key=lambda w: (w.start_ms, w.end_ms))
            ref = [Utt(u["speaker_id"], u["start_ms"], u["end_ms"], u["text"])
                   for u in json.loads((root / r["ref"]).read_text(encoding="utf-8"))["transcript"]]
            out.append(Case(f"{ds}/{r['id']}", ds, r["id"], root / r["audio"], r["split"], r["condition"],
                            int(r["duration_ms"]), int(r["num_speakers"]), ref, words,
                            [SpeakerTurn(*t) for t in full["turns"]]))
    return out


def final_utts(case: Case, turns, params: AlignParams) -> list[Utt]:
    us = align(case.words, turns, params)
    dur = case.duration_ms
    res = []
    for u in us:
        e = min(u.end_ms, dur)
        s = min(u.start_ms, max(0, e - 1))
        if u.text.strip() and e > s:
            res.append(Utt(u.speaker_id, s, e, u.text))
    return res


def score_case(case: Case, turns, params: AlignParams, with_cpwer=False) -> dict:
    h = final_utts(case, turns, params)
    d0 = sdiar.der(case.ref, h, 0.0, case.duration_ms)
    dn = sdiar.der(case.ref, h, 0.5, case.duration_ms)       # NIST +-0.25 s
    r = dict(key=case.key, cond=case.condition, ds=case.dataset, ref_n=sdiar.num_speakers(case.ref),
             hyp_n=sdiar.num_speakers(h),
             tot0=d0.total, err0=d0.errors, totn=dn.total, errn=dn.errors,
             missn=dn.missed, fan=dn.false_alarm, confn=dn.confusion,
             jer=sdiar.jer(case.ref, h, 0.0, case.duration_ms))
    if with_cpwer:
        c = swer.cpwer(case.ref, h, "norm").counts
        r["cp_err"], r["cp_ref"] = c.errors if hasattr(c, "errors") else (c.sub + c.dele + c.ins), c.ref_words if hasattr(c, "ref_words") else c.n_ref
    return r


def summarize(rows: list[dict]) -> dict:
    def agg(rs):
        if not rs:
            return {}
        t0, e0 = sum(x["tot0"] for x in rs), sum(x["err0"] for x in rs)
        tn, en = sum(x["totn"] for x in rs), sum(x["errn"] for x in rs)
        s = dict(n=len(rs), der0=e0 / t0, der_nist=en / tn, miss=sum(x["missn"] for x in rs) / tn,
                 fa=sum(x["fan"] for x in rs) / tn, conf=sum(x["confn"] for x in rs) / tn,
                 jer=sum(x["jer"] for x in rs if x["jer"] is not None) / max(1, sum(x["jer"] is not None for x in rs)),
                 spk_acc=sum(x["ref_n"] == x["hyp_n"] for x in rs) / len(rs),
                 spk_mae=sum(abs(x["ref_n"] - x["hyp_n"]) for x in rs) / len(rs),
                 spk_merge=sum(x["hyp_n"] < x["ref_n"] for x in rs) / len(rs),
                 spk_split=sum(x["hyp_n"] > x["ref_n"] for x in rs) / len(rs))
        if "cp_err" in rs[0]:
            s["cpwer"] = sum(x["cp_err"] for x in rs) / max(1, sum(x["cp_ref"] for x in rs))
        return s
    out = {"all": agg(rows)}
    for ds in sorted({r["ds"] for r in rows}):
        out[ds] = agg([r for r in rows if r["ds"] == ds])
    for c in sorted({r["cond"] for r in rows}):
        out["cond:" + c] = agg([r for r in rows if r["cond"] == c])
    return out


def fmt(s: dict) -> str:
    return (f"n={s['n']} DER0={s['der0']*100:.1f} DER±.25={s['der_nist']*100:.1f} "
            f"(miss {s['miss']*100:.1f} fa {s['fa']*100:.1f} conf {s['conf']*100:.1f}) JER={s['jer']*100:.1f} "
            f"spk_acc={s['spk_acc']*100:.0f}% merge={s['spk_merge']*100:.0f}% split={s['spk_split']*100:.0f}%"
            + (f" cpWER={s['cpwer']*100:.1f}" if 'cpwer' in s else ""))
