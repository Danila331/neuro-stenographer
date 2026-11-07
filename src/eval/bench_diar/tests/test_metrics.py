import random

from bench_diar.metrics import score


def _rand(rng, spk, dur, n):
    out = []
    for _ in range(n):
        a = rng.uniform(0, dur - 3)
        out.append((rng.choice(spk), round(a, 2), round(a + rng.uniform(0.5, 3), 2)))
    return out


def test_perfect():
    r = [("a", 0, 2), ("b", 2, 4), ("a", 3.5, 5)]
    s = score(r, [("x", 0, 2), ("y", 2, 4), ("x", 3.5, 5)], 6)
    assert s.der < 1e-6 and s.jer < 1e-6


def test_all_miss():
    s = score([("a", 0, 2)], [], 4)
    assert abs(s.der - 1) < 1e-6 and abs(s.miss - 1) < 1e-6


def test_confusion():
    s = score([("a", 0, 2), ("b", 2, 4)], [("x", 0, 3), ("y", 3, 4)], 4)
    assert abs(s.conf - 0.25) < 0.02


def test_vs_pyannote():
    from pyannote.core import Annotation, Segment
    from pyannote.metrics.diarization import DiarizationErrorRate
    rng = random.Random(0)
    for _ in range(5):
        ref = _rand(rng, "abc", 60, 20)
        hyp = _rand(rng, "xyzw", 60, 20)
        A, B = Annotation(), Annotation()
        for s, a, b in ref:
            A[Segment(a, b)] = s
        for s, a, b in hyp:
            B[Segment(a, b)] = s
        A, B = A.support(), B.support()  # same-speaker overlaps merged (our model)
        ref = [(l, seg.start, seg.end) for seg, _, l in A.itertracks(yield_label=True)]
        hyp = [(l, seg.start, seg.end) for seg, _, l in B.itertracks(yield_label=True)]
        want = DiarizationErrorRate(collar=0.0, skip_overlap=False)(A, B, uem=Segment(0, 60))
        got = score(ref, hyp, 60, 0.0).der
        assert abs(want - got) < 0.01, (want, got)
