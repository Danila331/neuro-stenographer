"""B50 summary: map-reduce over fragments, JSON by GBNF, link validation. Pure logic + one LLM call per fragment.

Kotlin port: `SummaryBuilder` (fragments, grammar, parse/validate/merge) in :core, the LLM call stays in `LlmController`.
"""
from __future__ import annotations

import json
import re
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

from .data import Record, fmt_time
from .text import terms

PROMPTS = Path(__file__).resolve().parent.parent / "prompts" / "android"
CHARS_PER_TOKEN = 2.3


# ---------------------------------------------------------------- data ---------------------------------------------------------------
@dataclass
class Point:
    speaker: int
    text: str
    refs: list[int]            # 0-based utterance indices of the record (after validation)


@dataclass
class Item:
    text: str
    refs: list[int]
    who: str = ""


@dataclass
class Topic:
    title: str
    start: int                 # 0-based utterance index
    end: int
    points: list[Point] = field(default_factory=list)
    decisions: list[Item] = field(default_factory=list)
    tasks: list[Item] = field(default_factory=list)
    centroid: object = None


@dataclass
class Stats:
    refs_total: int = 0
    refs_bad_range: int = 0     # number outside the fragment
    refs_bad_speaker: int = 0   # points: utterance belongs to another speaker
    relinked: int = 0           # unsupported reference replaced by the line that really says it
    items_total: int = 0
    items_dropped: int = 0
    parse_failed: int = 0
    repaired: int = 0


# ---------------------------------------------------------------- prompt / grammar -------------------------------------------------
def system_prompt() -> str:
    return (PROMPTS / "summary_system.txt").read_text(encoding="utf-8").strip("\n")


def line(rec: Record, i: int, local: int) -> str:
    u = rec.utts[i]
    return f"[{local}] {fmt_time(u.start_ms)} Говорящий {u.spk}: {u.text}"


def plan(rec: Record, vecs=None, target_chars: int = 1500, **kw) -> list[tuple[int, int]]:
    """Fragments [lo, hi): cut at embedding valleys (`segment.boundaries`) so that a fragment is about one topic and fits the budget.
    Without vectors: plain size-based cuts."""
    from .segment import boundaries
    n = len(rec.utts)
    chars = [len(line(rec, i, 99)) + 1 for i in range(n)]
    if vecs is not None:
        cuts = boundaries(vecs, chars, max_chars=target_chars, **kw)
    else:
        cuts, cur = [], 0
        for i in range(n):
            if cur and cur + chars[i] > target_chars:
                cuts.append(i)
                cur = 0
            cur += chars[i]
    edges = [0] + cuts + [n]
    return [(a, b) for a, b in zip(edges, edges[1:]) if b > a]


def _alt(nums) -> str:
    return " | ".join(f'"{n}"' for n in nums)


def grammar(rec: Record, lo: int, hi: int, max_str: int = 140, own_refs: bool = True) -> str:
    """GBNF for one fragment. Speakers and reference numbers are restricted to those that exist; a point of speaker K may cite only K's own lines."""
    n = hi - lo
    by_spk: dict[int, list[int]] = {}
    for i in range(lo, hi):
        by_spk.setdefault(rec.utts[i].spk, []).append(i - lo + 1)
    pts = " | ".join(f'point{k}' for k in sorted(by_spk))
    rules = []
    for k, nums in sorted(by_spk.items()):
        rules.append(f'point{k} ::= "{{\\"speaker\\":{k},\\"refs\\":[" r{k} ("," r{k}){{0,2}} "],\\"text\\":" str "}}"')
        rules.append(f"r{k} ::= {_alt(nums if own_refs else range(1, n + 1))}")
    return f'''root ::= "{{\\"title\\":" str ",\\"points\\":[" point ("," point){{0,5}} "],\\"decisions\\":[" (dec ("," dec){{0,2}})? "],\\"tasks\\":[" (task ("," task){{0,2}})? "]}}"
point ::= {pts}
dec ::= "{{\\"refs\\":[" ref ("," ref){{0,2}} "],\\"text\\":" str "}}"
task ::= "{{\\"refs\\":[" ref ("," ref){{0,2}} "],\\"who\\":" str ",\\"text\\":" str "}}"
ref ::= {_alt(range(1, n + 1))}
str ::= "\\"" [^"\\\\\\n]{{1,{max_str}}} "\\""
''' + "\n".join(rules) + "\n"


# ---------------------------------------------------------------- parse / validate -------------------------------------------------
def parse_json(text: str, stats: Stats) -> dict | None:
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        stats.parse_failed += 1
        return None


def _refs(raw, lo: int, hi: int, st: Stats) -> list[int]:
    out = []
    for r in raw or []:
        st.refs_total += 1
        if isinstance(r, int) and 1 <= r <= hi - lo:
            out.append(lo + r - 1)
        else:
            st.refs_bad_range += 1
    return out


def validate(d: dict, rec: Record, lo: int, hi: int, st: Stats, min_support: float = 0.3, relink: bool = True, item_support: float = 0.5) -> Topic | None:
    """One topic for fragment [lo, hi). Keeps only items whose references exist, belong to the stated speaker (points) and support the text.
    An item with an unsupported reference is re-linked to the best matching utterance of the fragment (same speaker for points) or dropped."""
    if not isinstance(d, dict) or not str(d.get("title", "")).strip():
        return None
    tp = Topic(str(d["title"]).strip(), lo, hi - 1)

    def settle(txt: str, refs: list[int], speaker: int | None, thr: float = min_support) -> list[int]:
        pool = [i for i in range(lo, hi) if speaker is None or rec.utts[i].spk == speaker]
        good = [r for r in refs if r in pool and _support(txt, rec.utts[r].text) >= thr]
        if not good and relink and pool:                  # the model cited the wrong line: look for the line that really says it
            best = max(pool, key=lambda i: _support(txt, rec.utts[i].text))
            if _support(txt, rec.utts[best].text) >= thr:
                st.relinked += 1
                good = [best]
        return sorted(set(good))

    for p in d.get("points", []):
        st.items_total += 1
        sp, txt = p.get("speaker"), str(p.get("text", "")).strip()
        refs = _refs(p.get("refs"), lo, hi, st)
        st.refs_bad_speaker += sum(1 for r in refs if rec.utts[r].spk != sp)
        good = settle(txt, refs, sp) if isinstance(sp, int) and txt else []
        if good:
            tp.points.append(Point(sp, txt, good))
        else:
            st.items_dropped += 1
    for key, dst in (("decisions", tp.decisions), ("tasks", tp.tasks)):
        for p in d.get(key, []):
            st.items_total += 1
            txt = str(p.get("text", "")).strip()
            refs = _refs(p.get("refs"), lo, hi, st)
            good = settle(txt, refs, None, item_support) if txt else []
            if good:
                dst.append(Item(txt, good, str(p.get("who", "")).strip()))
            else:
                st.items_dropped += 1
    return tp if (tp.points or tp.decisions or tp.tasks) else None


def _support(text: str, evidence: str) -> float:
    """Share of the item's content stems present in the cited utterance (numbers written as digits/words are compared as stems)."""
    a = set(terms(text))
    if not a:
        return 1.0
    return len(a & set(terms(evidence))) / len(a)


# ---------------------------------------------------------------- merge (reduce) ---------------------------------------------------
def _sim(a: str, b: str) -> float:
    x, y = set(terms(a)), set(terms(b))
    return len(x & y) / max(1, len(x | y))


def _dedup(items: list, key) -> list:
    out = []
    for it in items:
        if not any(_sim(key(it), key(o)) >= 0.7 for o in out):
            out.append(it)
    return out


def merge(topics: list[Topic], title_sim: float = 0.45, cos: float | None = None) -> list[Topic]:
    """Join neighbouring fragments about one topic (similar titles, or close centroids), dedupe points/decisions/tasks."""
    out: list[Topic] = []
    for t in sorted(topics, key=lambda x: x.start):
        if out and (_sim(out[-1].title, t.title) >= title_sim or (cos is not None and out[-1].centroid is not None and t.centroid is not None
                    and float(_cos(out[-1].centroid, t.centroid)) >= cos)):
            o = out[-1]
            o.end = max(o.end, t.end)
            o.points += t.points
            o.decisions += t.decisions
            o.tasks += t.tasks
            if o.centroid is not None and t.centroid is not None:
                o.centroid = (o.centroid + t.centroid) / 2
        else:
            out.append(t)
    for t in out:
        t.points = _dedup(t.points, lambda p: p.text)
        t.decisions = _dedup(t.decisions, lambda p: p.text)
        t.tasks = _dedup(t.tasks, lambda p: p.text)
    return out


def _cos(a, b) -> float:
    import numpy as np
    return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))


def to_json(rec: Record, topics: list[Topic], overview: str = "") -> dict:
    """The structure the UI renders. Times are derived from the referenced utterances, never generated by the model."""
    def ref(i):
        u = rec.utts[i]
        return {"n": i + 1, "t": u.start_ms, "speaker": u.speaker}
    return {"overview": overview, "topics": [{
        "title": t.title, "start_ms": rec.utts[t.start].start_ms, "end_ms": rec.utts[t.end].end_ms,
        "points": [{"speaker": f"speaker_{p.speaker}", "text": p.text, "refs": [ref(r) for r in p.refs]} for p in t.points],
        "decisions": [{"text": x.text, "refs": [ref(r) for r in x.refs]} for x in t.decisions],
        "tasks": [{"who": x.who, "text": x.text, "refs": [ref(r) for r in x.refs]} for x in t.tasks]} for t in topics]}


# ---------------------------------------------------------------- run ----------------------------------------------------------------
def summarize(srv, rec: Record, vecs=None, n_ctx: int = 4096, max_new: int = 700, target_chars: int = 1500, min_support: float = 0.3,
              use_grammar: bool = True, overview: bool = False, cache_prompt: bool = True, relink: bool = True, merge_sim: float = 0.45,
              merge_cos: float | None = None, seg_kw: dict | None = None, own_refs: bool = True, item_support: float = 0.5) -> dict:
    from .llm import chatml
    system = system_prompt()
    st = Stats()
    frags = plan(rec, vecs, target_chars, **(seg_kw or {}))
    topics: list[Topic] = []
    calls = []
    for lo, hi in frags:
        body = "\n".join(line(rec, i, i - lo + 1) for i in range(lo, hi))
        r = srv.complete(chatml(system, body), n_predict=max_new, grammar=grammar(rec, lo, hi, own_refs=own_refs) if use_grammar else None,
                         cache_prompt=cache_prompt)
        calls.append({k: r[k] for k in ("prompt_n", "cache_n", "prompt_ms", "predicted_n", "predicted_ms", "stop")})
        d = parse_json(_extract(r["text"]), st)
        t = validate(d, rec, lo, hi, st, min_support, relink, item_support) if d else None
        if t:
            t.centroid = None if vecs is None else vecs[lo:hi].mean(0)
            topics.append(t)
    merged = merge(topics, merge_sim, merge_cos)
    ov = make_overview(srv, merged) if (overview and merged) else ""
    return {"topics": merged, "raw_topics": len(topics), "stats": st, "calls": calls, "fragments": len(frags), "overview": ov}


def _extract(text: str) -> str:
    i = text.find("{")
    return text[i:] if i >= 0 else text


def make_overview(srv, topics: list[Topic]) -> str:
    from .llm import chatml
    sys = (PROMPTS / "summary_overview_system.txt").read_text(encoding="utf-8").strip("\n")
    body = "\n".join(f"- {t.title}: " + "; ".join(x.text for x in (t.decisions + t.points)[:3]) for t in topics)[:2200]
    r = srv.complete(chatml(sys, body), n_predict=120)
    return re.sub(r"\s+", " ", r["text"]).strip()
