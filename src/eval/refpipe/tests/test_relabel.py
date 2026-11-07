import json
from pathlib import Path

import numpy as np
import pytest

from refpipe.relabel import Params, cut, label_units, make_units, silhouette, ward_merges

CASES = json.loads((Path(__file__).parent / "relabel_cases.json").read_text())["cases"]


@pytest.mark.parametrize("c", CASES, ids=[c["name"] for c in CASES])
def test_case(c):
    p = Params(**c["params"])
    w = [tuple(x) for x in c["words"]]
    tt = [tuple(x) for x in c["turns"]] if c["turns"] else None
    units = make_units(w, tt, p)
    exp = c["expected"]
    assert [list(u) for u in units] == exp["units"]
    if "embeddings" not in c:
        return
    E = np.array(c["embeddings"], dtype=np.float64)
    E = E / (np.linalg.norm(E, axis=1, keepdims=True) + 1e-9)
    dur = np.array([u[1] - u[0] for u in units])
    diag = {}
    lab = label_units(E, dur, p, diag)
    assert [int(x) for x in lab] == exp["labels"]
    assert [[int(l), u[0], u[1]] for u, l in zip(units, lab)] == exp["turns"]
    assert diag.get("best_k") == exp["diag"]["best_k"] and bool(diag.get("k1", False)) == exp["diag"]["k1"]
    for k, v in exp["diag"]["silhouettes"].items():
        assert abs(diag["silhouettes"][int(k)] - v) < 1e-5


def test_ward_matches_scipy():
    from scipy.cluster.hierarchy import fcluster, linkage

    rng = np.random.default_rng(0)
    Y = np.concatenate([rng.normal(m, 0.15, (12, 8)) for m in (0, 1, -1)])
    Y /= np.linalg.norm(Y, axis=1, keepdims=True)
    mg = ward_merges(Y)
    Z = linkage(Y, "ward")
    for k in (2, 3, 4):
        a, b = cut(len(Y), mg, k), fcluster(Z, k, "maxclust") - 1
        assert len({(x, y) for x, y in zip(a, b)}) == k


def test_k1_guard_only_triggers_below_threshold():
    rng = np.random.default_rng(3)
    E = rng.normal(size=(20, 8))
    E /= np.linalg.norm(E, axis=1, keepdims=True)
    dur = np.full(20, 3000)
    assert len(set(label_units(E, dur, Params(k1_silhouette_thr=None)))) >= 2
    assert set(label_units(E, dur, Params(k1_silhouette_thr=0.99))) == {0}


def test_params_from_config_ignores_comment_keys():
    p = Params.from_config({"gap_ms": 200, "embed_max_s": 10, "k1_silhouette_thr": 0.1, "_pending_k1": "x", "enabled": True})
    assert p.gap_ms == 200 and p.embed_max_s == 10.0 and p.k1_silhouette_thr == 0.1
