import numpy as np
from scipy.cluster.hierarchy import fcluster, linkage

from bench_diar.spkphrase import Params, cut, label_units, make_units, silhouette, ward_merges


def test_ward_matches_scipy():
    rng = np.random.default_rng(0)
    Y = np.concatenate([rng.normal(m, 0.15, (12, 8)) for m in (0, 1, -1)])
    Y /= np.linalg.norm(Y, axis=1, keepdims=True)
    mg = ward_merges(Y)
    Z = linkage(Y, "ward")
    for k in (2, 3, 4):
        a = cut(len(Y), mg, k)
        b = fcluster(Z, k, "maxclust") - 1
        assert len({(x, y) for x, y in zip(a, b)}) == k   # same partition


def test_two_speakers_found():
    rng = np.random.default_rng(1)
    c = rng.normal(size=(2, 16))
    E = np.stack([c[i % 2] + 0.1 * rng.normal(size=16) for i in range(30)])
    E /= np.linalg.norm(E, axis=1, keepdims=True)
    lab = label_units(E, np.full(30, 2000), Params())
    assert len(set(lab)) == 2 and all(lab[i] == lab[i % 2] for i in range(30))


def test_units_gap_and_split():
    words = [(0, 400), (500, 900), (1000, 1400), (2500, 2900)]
    p = Params(gap_ms=300)
    u = make_units(words, None, p)
    assert [(a, b) for a, b, *_ in u] == [(0, 1400), (2500, 2900)]
    turns = [(0, 0, 950), (1, 950, 3000)]
    u = make_units(words, turns, Params(gap_ms=300, split_min_side_ms=300))
    assert [(a, b) for a, b, *_ in u] == [(0, 900), (1000, 1400), (2500, 2900)]
