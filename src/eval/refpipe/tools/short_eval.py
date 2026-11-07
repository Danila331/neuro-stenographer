"""Short-utterance and interruption metrics (BACKLOG B31).

    python tools/short_eval.py --dataset DS_ROOT [--dataset ...] --run RUN_DIR [--split dev|test|all] [--json OUT]

Items (from the reference): SHORT = utterance with <= 2 words and <= 1.2 s;  INTERRUPT = utterance of speaker B that
starts strictly inside an utterance of another speaker A (start > A.start and start <= A.end - 100 ms).
For every item the hypothesis words (RUN_DIR/<id>.full.json) whose midpoint lies in [start - 250, end + 250] ms are taken:
  found    >= 1 such word;
  partial  >= 1 reference word (normalised: lower, ё->е, letters/digits) occurs in them;
  exact    all reference words occur in them, in order (difflib matching blocks == len(ref));
  speaker  the hypothesis speaker of the matched word(s), mapped to reference speakers by the file-level assignment that
           maximises the overlap (Hungarian on utterance-interval overlap), equals the reference speaker
           (counted over items with partial >= 1).
Also printed: hypothesis words per item and the number of 'phantom' short hypothesis utterances (<= 2 words, <= 1.2 s) that
overlap no reference utterance by >= 40 % (insertions).
"""
from __future__ import annotations

import argparse
import csv
import json
import re
from collections import Counter
from difflib import SequenceMatcher
from pathlib import Path

import numpy as np
from scipy.optimize import linear_sum_assignment


def norm(t: str) -> list[str]:
    return re.sub(r"[^\w\s]", "", t.lower().replace("ё", "е")).split()


def speaker_map(ref: list[dict], hyp: list[dict]) -> dict[str, str]:
    rs = sorted({u["speaker_id"] for u in ref})
    hs = sorted({u["speaker_id"] for u in hyp})
    if not rs or not hs:
        return {}
    M = np.zeros((len(hs), len(rs)))
    for h in hyp:
        for r in ref:
            ov = min(h["end_ms"], r["end_ms"]) - max(h["start_ms"], r["start_ms"])
            if ov > 0:
                M[hs.index(h["speaker_id"]), rs.index(r["speaker_id"])] += ov
    i, j = linear_sum_assignment(-M)
    return {hs[a]: rs[b] for a, b in zip(i, j) if M[a, b] > 0}


def evaluate(datasets: list[Path], run: Path, split: str) -> dict:
    acc = {k: Counter() for k in ("short", "interrupt", "short_isolated")}
    phantom, hyp_short, nfiles = 0, 0, 0
    for root in datasets:
        for r in csv.DictReader((root / "manifest.csv").open(encoding="utf-8")):
            if split != "all" and r["split"] != split:
                continue
            fj = run / f"{r['id']}.full.json"
            if not fj.is_file():
                continue
            ref = json.loads((root / r["ref"]).read_text(encoding="utf-8"))["transcript"]
            full = json.loads(fj.read_text(encoding="utf-8"))
            hyp = [{"speaker_id": u["speaker_id"], "start_ms": u["start_ms"], "end_ms": u["end_ms"]} for u in full["utterances"]]
            words = [(w["text"], (w["start_ms"] + w["end_ms"]) / 2, u["speaker_id"]) for u in full["utterances"] for w in u["words"]]
            mp = speaker_map(ref, hyp)
            nfiles += 1
            for i, u in enumerate(ref):
                nw, dur = len(norm(u["text"])), u["end_ms"] - u["start_ms"]
                others = [v for v in ref if v["speaker_id"] != u["speaker_id"]]
                inter = any(v["start_ms"] < u["start_ms"] <= v["end_ms"] - 100 for v in others)
                short = nw <= 2 and dur <= 1200
                kinds = [k for k, c in (("short", short), ("interrupt", inter)) if c]
                if short and not any(v["start_ms"] - 300 < u["end_ms"] and v["end_ms"] + 300 > u["start_ms"] for v in ref if v is not u):
                    kinds.append("short_isolated")
                if not kinds:
                    continue
                win = [w for w in words if u["start_ms"] - 250 <= w[1] <= u["end_ms"] + 250]
                rw = norm(u["text"])
                hw = [x for t, _, _ in win for x in norm(t)]
                blocks = SequenceMatcher(None, rw, hw, autojunk=False).get_matching_blocks()
                hit = sum(b.size for b in blocks)
                matched_spk = [s for t, _, s in win if norm(t) and norm(t)[0] in rw]
                spk_ok = bool(matched_spk) and Counter(mp.get(s) for s in matched_spk).most_common(1)[0][0] == u["speaker_id"]
                for k in kinds:
                    a = acc[k]
                    a["n"] += 1
                    a["found"] += bool(win)
                    a["partial"] += hit >= 1
                    a["exact"] += hit == len(rw)
                    a["spk_den"] += hit >= 1
                    a["spk_ok"] += hit >= 1 and spk_ok
                    a["hyp_words"] += len(win)
            for u in full["utterances"]:
                if len(u["words"]) <= 2 and u["end_ms"] - u["start_ms"] <= 1200:
                    hyp_short += 1
                    ov = max((min(u["end_ms"], v["end_ms"]) - max(u["start_ms"], v["start_ms"]) for v in ref), default=0)
                    phantom += ov < 0.4 * (u["end_ms"] - u["start_ms"])
    out = {"files": nfiles, "hyp_short_utts": hyp_short, "phantom_short_utts": phantom}
    for k, a in acc.items():
        n = a["n"] or 1
        out[k] = {"n": a["n"], "found%": round(100 * a["found"] / n, 1), "partial%": round(100 * a["partial"] / n, 1),
                  "exact%": round(100 * a["exact"] / n, 1),
                  "speaker%": round(100 * a["spk_ok"] / max(a["spk_den"], 1), 1), "hyp_words/item": round(a["hyp_words"] / n, 2)}
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", action="append", required=True)
    ap.add_argument("--run", required=True)
    ap.add_argument("--split", default="all")
    ap.add_argument("--json", default=None)
    a = ap.parse_args()
    res = evaluate([Path(d) for d in a.dataset], Path(a.run), a.split)
    print(json.dumps(res, ensure_ascii=False))
    if a.json:
        Path(a.json).write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
