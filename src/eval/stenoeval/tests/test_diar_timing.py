import pytest

from stenoeval.diar import der, jer, num_speakers, to_annotation
from stenoeval.timing import HypWord, timing
from stenoeval.transcript import Utt


def U(spk, s, e, text="слово"):
    return Utt(spk, s, e, text)


REF = [U("speaker_1", 0, 2000), U("speaker_2", 2000, 4000), U("speaker_1", 4000, 6000)]


def test_der_perfect_and_swapped_labels():
    assert der(REF, REF).der == 0.0
    swapped = [U("speaker_2", 0, 2000), U("speaker_1", 2000, 4000), U("speaker_2", 4000, 6000)]
    assert der(REF, swapped).der == 0.0
    assert jer(REF, swapped) == 0.0


def test_der_components():
    hyp = [U("speaker_1", 0, 2000), U("speaker_1", 2000, 4000), U("speaker_1", 4000, 5000)]
    d = der(REF, hyp, 0.0, 6000)
    assert d.total == pytest.approx(6.0)
    assert d.confusion == pytest.approx(2.0)
    assert d.missed == pytest.approx(1.0)
    assert d.false_alarm == pytest.approx(0.0)
    assert d.der == pytest.approx(0.5)
    fa = der(REF, REF + [U("speaker_1", 6000, 7000)], 0.0, 8000)
    assert fa.false_alarm == pytest.approx(1.0)


def test_der_collar_helps():
    hyp = [U("speaker_1", 100, 2100), U("speaker_2", 2100, 4100), U("speaker_1", 4100, 6000)]
    assert der(REF, hyp, 0.0).der > der(REF, hyp, 0.25).der > 0 or der(REF, hyp, 0.25).der == 0
    assert der(REF, hyp, 0.5).der == pytest.approx(0.0)


def test_der_overlap_is_scored():
    ref = [U("speaker_1", 0, 4000), U("speaker_2", 2000, 6000)]
    one = [U("speaker_1", 0, 4000), U("speaker_2", 4000, 6000)]    # overlap region 2-4 s: speaker_2 missed
    d = der(ref, one, 0.0, 6000)
    assert d.total == pytest.approx(8.0)
    assert d.missed == pytest.approx(2.0)
    # overlapping utterances of the same speaker are merged, not double counted
    ann = to_annotation([U("speaker_1", 0, 3000), U("speaker_1", 2000, 5000)])
    assert len(ann) == 1


def test_der_edge_cases():
    assert der([], []).der == 0.0
    assert der(REF, []).der == pytest.approx(1.0)
    assert der([], REF).der is None
    assert jer([], []) == 0.0 and jer([], REF) is None and jer(REF, []) == 1.0


def test_one_speaker():
    ref = [U("speaker_1", 0, 3000)]
    assert der(ref, [U("speaker_1", 0, 3000)]).der == 0.0
    assert num_speakers(ref) == 1 and num_speakers([]) == 0


def test_timing_exact_and_shifted():
    ref = [U("speaker_1", 1000, 3000, "раз два три четыре"), U("speaker_2", 4000, 6000, "пять шесть семь")]
    t = timing(ref, ref)
    assert t.start_mae_ms == 0 and t.end_mae_ms == 0 and t.start_acc300 == 1.0
    shifted = [U("speaker_1", 1200, 3200, "раз два три четыре"), U("speaker_2", 4500, 6100, "пять шесть семь")]
    t = timing(ref, shifted)
    assert t.start_mae_ms == pytest.approx(350)       # (200 + 500) / 2
    assert t.end_mae_ms == pytest.approx(150)         # (200 + 100) / 2
    assert t.start_acc300 == 0.5 and t.n_matched == 2


def test_timing_different_segmentation():
    # hypothesis merges two reference utterances into one: time is split proportionally per word
    ref = [U("speaker_1", 0, 2000, "аааа бббб"), U("speaker_1", 3000, 5000, "вввв гггг")]
    hyp = [U("speaker_1", 0, 5000, "аааа бббб вввв гггг")]
    t = timing(ref, hyp)
    assert t.n_matched == 2
    assert t.start_mae_ms < 800 and t.end_mae_ms < 800


def test_timing_unmatched_and_empty():
    ref = [U("speaker_1", 0, 1000, "кошка"), U("speaker_1", 2000, 3000, "собака")]
    t = timing(ref, [U("speaker_1", 0, 1000, "кошка")])
    assert t.n_matched == 1 and t.start_acc300 == 0.5
    t = timing(ref, [])
    assert t.n_matched == 0 and t.start_mae_ms is None and t.start_acc300 == 0.0
    t = timing([], ref)
    assert t.n_ref_utts == 0 and t.start_mae_ms is None and t.start_acc300 is None


def test_timing_word_level_hypothesis():
    ref = [U("speaker_1", 1000, 2000, "раз два")]
    words = [HypWord("раз", 1050, 1400), HypWord("два", 1500, 1950)]
    t = timing(ref, [], hyp_words=words)
    assert t.start_mae_ms == 50 and t.end_mae_ms == 50


def test_timing_substitution_fallback():
    ref = [U("speaker_1", 1000, 2000, "кошка")]
    t = timing(ref, [U("speaker_1", 1100, 2100, "мошка")])
    assert t.n_matched == 1 and t.start_mae_ms == 100
