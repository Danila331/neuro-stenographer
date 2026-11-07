"""Generate tests/relabel_cases.json: reference cases for the Kotlin port of refpipe/relabel.py.

Each case: params, words [[start_ms, end_ms]], turns [[speaker, start_ms, end_ms]] | null, embeddings (RAW per-unit extractor
outputs, 6 decimals; the port must L2-normalise them: e / (|e| + 1e-9)), and the expected result: units
[[start, end, w0, w1]], labels per unit, turns [[label, start, end]], diag (silhouette per k, best_k, k1).
Cases without "embeddings" only check the unit segmentation.   python tools/make_relabel_cases.py
"""
from __future__ import annotations

import json
from dataclasses import asdict
from pathlib import Path

import numpy as np

from refpipe.relabel import Params, label_units, make_units

OUT = Path(__file__).resolve().parents[1] / "tests" / "relabel_cases.json"


def r6(a):
    return [[round(float(v), 6) for v in row] for row in a]


def clusters(rng, centers, assign, noise, dim=8, scale=1.0):
    return np.stack([centers[c] * scale + noise * rng.normal(size=dim) for c in assign])


def words_for(durs, gap=500, t0=0):
    out, t = [], t0
    for d in durs:
        d = int(d)
        out.append([t, t + d])
        t += d + gap
    return out


def run(case):
    p = Params(**case["params"])
    w = [tuple(x) for x in case["words"]]
    tt = [tuple(x) for x in case["turns"]] if case["turns"] else None
    units = make_units(w, tt, p)
    res = {"units": [list(map(int, u)) for u in units]}
    if "embeddings" in case:
        E = np.array(case["embeddings"], dtype=np.float64)
        E = E / (np.linalg.norm(E, axis=1, keepdims=True) + 1e-9)
        assert len(E) == len(units), (case["name"], len(E), len(units))
        dur = np.array([u[1] - u[0] for u in units])
        diag = {}
        lab = label_units(E, dur, p, diag)
        res["labels"] = [int(x) for x in lab]
        res["turns"] = [[int(l), u[0], u[1]] for u, l in zip(units, lab)]
        res["diag"] = {"silhouettes": {str(k): round(v, 6) for k, v in diag.get("silhouettes", {}).items()},
                       "best_k": diag.get("best_k"), "k1": bool(diag.get("k1", False))}
    return res


def main() -> None:
    rng = np.random.default_rng(7)
    base = dict(gap_ms=200, max_unit_ms=12000, split_min_side_ms=300, pad_ms=40, embed_max_s=10.0, min_reliable_ms=800,
                k_min=2, k_max=6, max_cluster_units=400, refine=1, k1_silhouette_thr=None)
    C = rng.normal(size=(4, 8))
    cases = []

    def add(name, desc, words, turns, params=None, emb=None):
        c = {"name": name, "description": desc, "params": {**base, **(params or {})}, "words": words, "turns": turns}
        if emb is not None:
            c["embeddings"] = r6(emb)
        c["expected"] = run(c)
        cases.append(c)

    # --- unit segmentation -------------------------------------------------------------------------------------
    add("units_gap", "pauses <= gap_ms keep words in one unit, a longer pause starts a new one",
        [[0, 400], [500, 900], [1000, 1400], [2500, 2900]], None, {"gap_ms": 300})
    add("units_max_unit", "a unit never spans more than max_unit_ms",
        [[0, 3000], [3100, 6000], [6100, 9000], [9100, 12000], [12100, 15000]], None, {"gap_ms": 300, "max_unit_ms": 9500})
    add("units_split_by_turns", "cut between words with different dominant turns if both sides >= split_min_side_ms",
        [[0, 400], [500, 900], [1000, 1400], [2500, 2900]], [[0, 0, 950], [1, 950, 3000]], {"gap_ms": 300})
    add("units_split_side_too_short", "no cut when one side would be shorter than split_min_side_ms",
        [[0, 400], [500, 900], [1000, 1400]], [[0, 0, 950], [1, 950, 3000]], {"gap_ms": 300, "split_min_side_ms": 600})
    add("units_dominant_tie_first_turn", "equal overlap: the first turn in the list wins; a word without any turn has no dominant",
        [[0, 400], [500, 1000], [1100, 1500], [4000, 4400]], [[1, 0, 450], [0, 450, 1100], [2, 1300, 1500]], {"gap_ms": 300, "split_min_side_ms": 100})
    add("units_no_words", "no words -> no units", [], None)

    # --- clustering --------------------------------------------------------------------------------------------
    n = 24
    durs = list(rng.integers(1500, 4000, size=n))
    assign = [i % 2 for i in range(n)]
    add("two_speakers", "alternating phrases of two speakers (k=2)", words_for(durs), None, None,
        clusters(rng, C, assign, 0.25) * rng.uniform(0.5, 2.0, size=(n, 1)))
    n = 30
    durs = list(rng.integers(1200, 5000, size=n))
    assign = [int(x) for x in rng.integers(0, 3, size=n)]
    add("three_speakers", "three speakers in random order (k=3 has the best silhouette)", words_for(durs), None, None,
        clusters(rng, C, assign, 0.2))
    n = 20
    durs = [3000] * n
    mono = C[0] + 0.6 * rng.normal(size=(n, 8))
    add("monologue_no_guard", "one speaker, noisy embeddings, guard off -> the search still returns k>=2 (best silhouette is low, ~0.33)",
        words_for(durs), None, None, mono)
    add("monologue_guard", "same data, k1_silhouette_thr=0.5 -> the best silhouette is below it -> everyone is label 0",
        words_for(durs), None, {"k1_silhouette_thr": 0.5}, mono)
    add("dialogue_guard_passes", "two speakers: the silhouette is above the guard threshold -> k=2",
        words_for([2500] * 16), None, {"k1_silhouette_thr": 0.1}, clusters(rng, C, [i % 2 for i in range(16)], 0.2))
    add("fewer_than_three_units", "< 3 units -> one label", words_for([2000, 2500]), None, None, C[:2] + 0.05)
    # short unreliable units get labelled by the nearest centroid, but do not take part in clustering
    durs = [3000, 3000, 3000, 3000, 3000, 3000, 500, 600, 400, 700]
    assign = [0, 1, 0, 1, 0, 1, 0, 1, 1, 0]
    add("short_units_labelled_by_centroid", "units < min_reliable_ms are excluded from clustering and get the nearest centroid",
        words_for(durs), None, None, clusters(rng, C, assign, 0.15))
    durs = [3000] * 10
    assign = [0, 1, 0, 1, 0, 1, 0, 1, 0, 1]
    add("max_cluster_units", "more reliable units than max_cluster_units: only the longest ones are clustered",
        words_for([3000, 2900, 2800, 2700, 3100, 3200, 2000, 2100, 1900, 2200]), None, {"max_cluster_units": 6},
        clusters(rng, C, assign, 0.15))
    e14 = clusters(rng, C, [i % 3 for i in range(14)], 0.35)
    for rf in (0, 1, 3):
        add(f"refine_{rf}", f"same embeddings, refine={rf}: {rf} extra rounds of centroid re-estimation from all units",
            words_for([2500] * 14), None, {"refine": rf}, e14)
    add("k_max_limits_search", "k_max=2 stops the search at 2 clusters although 4 speakers are present", words_for([2500] * 16), None,
        {"k_max": 2}, clusters(rng, C, [i % 4 for i in range(16)], 0.1))
    # cut by turns then cluster
    words = [[0, 900], [1000, 1900], [3400, 4300], [4400, 5300], [5400, 6300], [6400, 7300]]
    turns = [[0, 0, 2000], [0, 3400, 5350], [1, 5350, 7400]]
    add("split_then_cluster", "a run of words is cut at the turn change into two units; 3 units in total (k_min=2)",
        words, turns, {"gap_ms": 300, "split_min_side_ms": 300, "min_reliable_ms": 800},
        np.stack([C[0] + 0.05 * rng.normal(size=8), C[0] + 0.05 * rng.normal(size=8), C[1] + 0.05 * rng.normal(size=8)]))
    OUT.write_text(json.dumps({"version": 1, "description": __doc__.strip().splitlines()[0] + " " + " ".join(__doc__.strip().splitlines()[1:6]),
                               "cases": cases}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(len(cases), "cases ->", OUT)


if __name__ == "__main__":
    main()
