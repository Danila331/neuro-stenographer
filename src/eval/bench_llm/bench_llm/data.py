"""Datasets: FLEURS ru (CC-BY-4.0) sentences and hand-written Kotovitsa dialogues."""
from __future__ import annotations

import csv
import random
import re
from dataclasses import dataclass
from pathlib import Path

from .text import parse, render

HERE = Path(__file__).resolve().parent.parent
DATA = HERE / "data"


@dataclass
class Utt:
    id: str
    text: str            # gold punctuated text
    words: list[str]     # normalized word sequence
    speaker: int = 0     # 1-based speaker (dialogues)


def load_fleurs(split: str, n: int, seed: int = 0, min_words: int = 6, max_words: int = 40) -> list[Utt]:
    path = DATA / f"fleurs_ru_{split}.tsv"
    seen, rows = set(), []
    with open(path, encoding="utf-8") as f:
        for r in csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
            if len(r) < 3 or r[0] in seen:
                continue
            seen.add(r[0])
            toks = parse(r[2])
            if min_words <= len(toks) <= max_words:
                rows.append(Utt(f"fleurs-{split}-{r[0]}", r[2], [t.w for t in toks]))
    rnd = random.Random(seed)
    rnd.shuffle(rows)
    return rows[:n]


@dataclass
class Dialog:
    id: str
    title: str
    turns: list[Utt]
    # synthetic timeline (ms) per turn, 130 words/min speech + pauses
    times: list[tuple[int, int]]


def load_dialogs() -> list[Dialog]:
    out = []
    for p in sorted((DATA / "dialogs").glob("*.txt")):
        title, turns = p.stem, []
        for line in p.read_text(encoding="utf-8").splitlines():
            if line.startswith("# title:"):
                title = line.split(":", 1)[1].strip()
            elif re.match(r"^\d+:", line):
                spk, text = line.split(":", 1)
                text = text.strip()
                turns.append(Utt(f"{p.stem}-{len(turns):02d}", text, [t.w for t in parse(text)], int(spk)))
        rnd = random.Random(p.stem)
        t, times = 1200, []
        for u in turns:
            dur = int(len(u.words) * 60000 / 130 * rnd.uniform(0.9, 1.1))
            times.append((t, t + dur))
            t += dur + rnd.randint(250, 900)
        out.append(Dialog(p.stem, title, turns, times))
    return out


def inject_boundary_errors(dlg: Dialog, seed: int = 0, p_err: float = 0.8, max_shift: int = 3):
    """Per-word speaker labels with Aligner-like errors at turn boundaries.

    For each speaker change, with prob p_err the boundary is shifted by 1..max_shift words
    (either the tail of the previous turn gets the next speaker or the head of the next turn
    gets the previous one). Returns (words, gold_labels, noisy_labels).
    """
    rnd = random.Random(f"{dlg.id}-{seed}")
    words, gold, turn_start = [], [], []
    for u in dlg.turns:
        turn_start.append(len(words))
        words += u.words
        gold += [u.speaker] * len(u.words)
    noisy = list(gold)
    for k in range(1, len(dlg.turns)):
        a, b = dlg.turns[k - 1], dlg.turns[k]
        if a.speaker == b.speaker or rnd.random() > p_err:
            continue
        s = turn_start[k]
        shift = rnd.randint(1, max_shift)
        if rnd.random() < 0.5:   # tail of previous turn labelled as next speaker
            shift = min(shift, len(a.words) - 1)
            for i in range(s - shift, s):
                noisy[i] = b.speaker
        else:                    # head of next turn labelled as previous speaker
            shift = min(shift, len(b.words) - 1)
            for i in range(s, s + shift):
                noisy[i] = a.speaker
    return words, gold, noisy


__all__ = ["Utt", "Dialog", "load_fleurs", "load_dialogs", "inject_boundary_errors", "render"]
