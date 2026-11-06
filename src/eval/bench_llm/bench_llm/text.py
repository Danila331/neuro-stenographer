"""Text utilities: guard (ARCHITECTURE.md §6), parsing of punctuated Russian text, F1 metrics."""
from __future__ import annotations

import re
from dataclasses import dataclass

MARKS = [",", ".", "?", "!", "—"]
_DASHES = {"—", "–", "-", "−", "―"}
_OPEN = "«\"„“”‘’'([{<"
_ALNUM = re.compile(r"[0-9A-Za-zА-Яа-яЁё]")


def norm(s: str) -> str:
    """ARCHITECTURE.md §6: lower -> ё→е -> keep letters/digits/spaces -> collapse spaces."""
    s = s.lower().replace("ё", "е")
    s = "".join(ch if (ch.isalnum() or ch.isspace()) else "" for ch in s)
    return " ".join(s.split())


def guard_ok(inp: str, out: str) -> bool:
    return norm(out).split() == norm(inp).split()


@dataclass
class Tok:
    w: str          # normalized word (lower, ё→е, alnum only)
    cap: bool       # first letter uppercase
    mark: str       # "", "," ".", "?", "!", "—" after the word


def _mark_from_run(run: str) -> str:
    if "?" in run:
        return "?"
    if "!" in run:
        return "!"
    if "…" in run or "." in run:
        return "."
    if "," in run or ";" in run:
        return ","
    if any(ch in run for ch in "—–"):
        return "—"
    return ""  # ':' and quotes etc. are not evaluated


def parse(text: str) -> list[Tok]:
    """Parse punctuated text into word/case/mark tokens. Hyphenated words are split into two."""
    toks: list[Tok] = []
    for raw in text.split():
        if raw in _DASHES or raw.strip("«»\"") in _DASHES:
            if toks and toks[-1].mark == "":
                toks[-1].mark = "—"
            continue
        t = raw.lstrip(_OPEN)
        # trailing run of non-alnum characters
        m = re.search(r"[^0-9A-Za-zА-Яа-яЁё]+$", t)
        run = m.group(0) if m else ""
        body = t[: len(t) - len(run)] if m else t
        mark = _mark_from_run(run)
        parts = [p for p in re.split(r"[-–—‑]", body) if p]
        parts = ["".join(ch for ch in p if _ALNUM.match(ch)) for p in parts]
        parts = [p for p in parts if p]
        if not parts:
            if toks and mark and toks[-1].mark == "":
                toks[-1].mark = mark
            continue
        for i, p in enumerate(parts):
            toks.append(Tok(p.lower().replace("ё", "е"), p[0].isupper(), mark if i == len(parts) - 1 else ""))
    return toks


def words_of(text: str) -> list[str]:
    return [t.w for t in parse(text)]


def render(toks: list[Tok]) -> str:
    out = []
    for t in toks:
        w = (t.w[:1].upper() + t.w[1:]) if t.cap else t.w
        if t.mark == "—":
            w += " —"
        else:
            w += t.mark
        out.append(w)
    return " ".join(out)


def fallback(words: list[str]) -> list[Tok]:
    """§6 fallback: input words + capital first letter + period at the end."""
    toks = [Tok(w, False, "") for w in words]
    if toks:
        toks[0].cap = True
        toks[-1].mark = "."
    return toks


@dataclass
class Counts:
    tp: int = 0
    fp: int = 0
    fn: int = 0

    def add(self, o: "Counts") -> None:
        self.tp += o.tp
        self.fp += o.fp
        self.fn += o.fn

    @property
    def p(self) -> float:
        return self.tp / (self.tp + self.fp) if self.tp + self.fp else 0.0

    @property
    def r(self) -> float:
        return self.tp / (self.tp + self.fn) if self.tp + self.fn else 0.0

    @property
    def f1(self) -> float:
        p, r = self.p, self.r
        return 2 * p * r / (p + r) if p + r else 0.0


def score(gold: list[Tok], pred: list[Tok]) -> dict[str, Counts]:
    """Per-position comparison; gold and pred must have the same words (guard passed or fallback)."""
    assert len(gold) == len(pred), (len(gold), len(pred))
    res = {k: Counts() for k in MARKS + ["marks", "caps", "caps_inner"]}
    for i, (g, p) in enumerate(zip(gold, pred)):
        for mk in MARKS:
            if g.mark == mk and p.mark == mk:
                res[mk].tp += 1
                res["marks"].tp += 1
            else:
                if p.mark == mk:
                    res[mk].fp += 1
                    res["marks"].fp += 1
                if g.mark == mk:
                    res[mk].fn += 1
                    res["marks"].fn += 1
        # case
        for key, use in (("caps", True), ("caps_inner", i > 0 and gold[i - 1].mark not in (".", "?", "!"))):
            if not use:
                continue
            if g.cap and p.cap:
                res[key].tp += 1
            elif p.cap:
                res[key].fp += 1
            elif g.cap:
                res[key].fn += 1
    return res


def total(results: list[dict[str, Counts]]) -> dict[str, Counts]:
    out = {k: Counts() for k in MARKS + ["marks", "caps", "caps_inner"]}
    for r in results:
        for k, c in r.items():
            out[k].add(c)
    return out
