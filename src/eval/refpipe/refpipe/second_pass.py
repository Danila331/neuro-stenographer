"""Optional second ASR pass (hard tier): Whisper re-decodes chunks where the first pass is unsure.

Trigger `low_confidence`: mean word confidence of the chunk < `confidence_threshold`.
Whisper gives text only (no token times), so its words are mapped onto the first-pass timing with a
word-level diff (difflib, normalised words): equal words keep the first-pass time and confidence,
replaced/inserted words are spread proportionally to their length over the time span of the words they
replace (or the gap between neighbours).  Whisper output whose word count is outside
[0.5x, 2x] of the first pass (hallucination / truncation) is ignored.
"""
from __future__ import annotations

import difflib
from pathlib import Path

import numpy as np

from .asr import AsrEngine, plain_word
from .punct import norm
from .types import TimeSpan, Word


def merge_second_pass(first: list[Word], second: list[str], keep_punct: bool = False) -> list[Word]:
    a = [norm(w.text) for w in first]
    b = [norm(w) for w in second]
    surface = [w if keep_punct else plain_word(w) for w in second]
    out: list[Word] = []

    def spread(js: list[int], t0: int, t1: int):
        lens = [len(surface[j]) + 1 for j in js]
        tot = sum(lens)
        acc = 0
        span = max(t1 - t0, len(js))
        for j, ln in zip(js, lens):
            s = t0 + span * acc // tot
            acc += ln
            e = t0 + span * acc // tot
            if surface[j]:
                out.append(Word(surface[j], s, max(e - 1, s + 1)))

    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
        if tag == "equal":
            for k in range(i2 - i1):
                f = first[i1 + k]
                out.append(Word(surface[j1 + k] or f.text, f.start_ms, f.end_ms, f.confidence))
        elif tag == "delete":
            continue
        else:  # replace / insert
            if i2 > i1:
                t0, t1 = first[i1].start_ms, first[i2 - 1].end_ms
            else:
                prev_end = first[i1 - 1].end_ms if i1 > 0 else (first[i1].start_ms if i1 < len(first) else 0)
                nxt = first[i1].start_ms if i1 < len(first) else prev_end + 80 * (j2 - j1)
                t0, t1 = prev_end, max(nxt, prev_end + (j2 - j1))
            spread(list(range(j1, j2)), t0, t1)
    # keep time monotonic
    fixed: list[Word] = []
    for w in out:
        s = max(w.start_ms, fixed[-1].start_ms) if fixed else w.start_ms
        fixed.append(Word(w.text, s, max(w.end_ms, s + 1), w.confidence))
    return fixed


class SecondPass:
    def __init__(self, sp_cfg: dict, asr_cfg: dict, models_root: Path, num_threads: int):
        self.thr = float(sp_cfg.get("confidence_threshold", 0.6))
        cfg = dict(asr_cfg)
        cfg.update(engine=sp_cfg["engine"], model_dir=sp_cfg["model_dir"], decoding="greedy_search",
                   batch_size=1)
        self.eng = AsrEngine(cfg, models_root, num_threads)

    def apply(self, x: np.ndarray, chunks: list[TimeSpan], words: list[Word], keep_punct: bool):
        out: list[Word] = []
        redone = 0
        wi = 0
        for c in chunks:
            cw = []
            while wi < len(words) and words[wi].start_ms < c.end_ms:
                cw.append(words[wi])
                wi += 1
            confs = [w.confidence for w in cw if w.confidence is not None]
            if cw and confs and sum(confs) / len(confs) < self.thr:
                (_, r), = self.eng.recognize(x, [c])
                sec = r.text.split()
                if 0.5 * len(cw) <= len(sec) <= 2 * len(cw) + 1:
                    cw = merge_second_pass(cw, sec, keep_punct)
                    redone += 1
            out.extend(cw)
        out.extend(words[wi:])
        return out, redone
