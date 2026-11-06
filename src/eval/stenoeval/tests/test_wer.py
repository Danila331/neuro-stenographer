import pytest

from stenoeval.transcript import Utt
from stenoeval.wer import align, cpwer, edit_distance, wer, wer_tokens


def U(spk, s, e, text):
    return Utt(spk, s, e, text)


def test_edit_distance_and_counts():
    assert edit_distance([], []) == 0
    assert edit_distance(list("abc"), []) == 3
    assert edit_distance([], list("abc")) == 3
    assert edit_distance(list("kitten"), list("sitting")) == 3
    c = wer_tokens("a b c d".split(), "a x c".split())
    assert (c.sub, c.dele, c.ins, c.ref_words) == (1, 1, 0, 4)
    assert c.wer == 0.5
    c = wer_tokens("a".split(), "a b c".split())
    assert (c.sub, c.dele, c.ins) == (0, 0, 2) and c.wer == 2.0


def test_align_consistent_with_distance():
    import random

    rnd = random.Random(1)
    for _ in range(50):
        a = [rnd.choice("abc") for _ in range(rnd.randint(0, 9))]
        b = [rnd.choice("abc") for _ in range(rnd.randint(0, 9))]
        ops = align(a, b)
        errs = sum(o != "C" for o, _, _ in ops)
        assert errs == edit_distance(a, b)
        assert [i for _, i, _ in ops if i is not None] == list(range(len(a)))
        assert [j for _, _, j in ops if j is not None] == list(range(len(b)))


def test_empty_cases():
    assert wer([], [], "norm").wer == 0.0
    assert wer([], [U("speaker_1", 0, 1, "мяу")], "norm").errors == 1
    c = wer([U("speaker_1", 0, 1, "мяу мяу")], [], "norm")
    assert c.dele == 2 and c.wer == 1.0


def test_modes_differ():
    ref = [U("speaker_1", 0, 1000, "Привет, Ёжик! 25 котов.")]
    hyp = [U("speaker_1", 0, 1000, "привет ежик двадцать пять котов")]
    assert wer(ref, hyp, "raw").errors == 5       # every raw token differs/shifts
    assert wer(ref, hyp, "norm").errors == 2      # "25" -> sub + insertion
    assert wer(ref, hyp, "norm_num").errors == 0


def test_concatenation_follows_start_ms():
    ref = [U("speaker_1", 0, 1, "раз"), U("speaker_2", 5, 6, "два"), U("speaker_1", 9, 10, "три")]
    hyp = [U("speaker_1", 9, 10, "три"), U("speaker_1", 0, 1, "раз"), U("speaker_2", 5, 6, "два")]  # file order shuffled
    assert wer(ref, hyp, "norm").errors == 0


def test_cpwer_swapped_ids_is_zero_but_plain_wer_is_not_affected():
    ref = [U("speaker_1", 0, 1, "раз два"), U("speaker_2", 2, 3, "три четыре")]
    hyp = [U("speaker_1", 0, 1, "раз два"), U("speaker_2", 2, 3, "три четыре")]
    swapped = [U("speaker_2", 0, 1, "раз два"), U("speaker_1", 2, 3, "три четыре")]
    assert cpwer(ref, hyp, "norm").counts.errors == 0
    r = cpwer(ref, swapped, "norm")
    assert r.counts.errors == 0 and r.mapping == {"speaker_1": "speaker_2", "speaker_2": "speaker_1"}
    assert wer(ref, swapped, "norm").errors == 0


def test_cpwer_wrong_attribution_costs():
    ref = [U("speaker_1", 0, 1, "раз два"), U("speaker_2", 2, 3, "три четыре")]
    hyp = [U("speaker_1", 0, 1, "раз два три"), U("speaker_2", 2, 3, "четыре")]  # word 'три' to the wrong speaker
    r = cpwer(ref, hyp, "norm")
    assert r.counts.errors == 2          # one insertion at speaker_1, one deletion at speaker_2
    assert wer(ref, hyp, "norm").errors == 0


def test_cpwer_speaker_count_mismatch():
    ref = [U("speaker_1", 0, 1, "раз два"), U("speaker_2", 2, 3, "три четыре")]
    one = [U("speaker_1", 0, 3, "раз два три четыре")]
    r = cpwer(ref, one, "norm")
    assert r.counts.errors == 4 and r.counts.ref_words == 4   # 2 inserted + 2 deleted
    three = ref + [U("speaker_3", 4, 5, "лишнее слово")]
    r = cpwer(ref, three, "norm")
    assert r.counts.errors == 2 and r.counts.ins == 2
    assert cpwer([], [], "norm").counts.errors == 0
    assert cpwer(ref, [], "norm").counts.dele == 4


def test_cpwer_single_speaker_equals_wer():
    ref = [U("speaker_1", 0, 1, "а б в"), U("speaker_1", 2, 3, "г д")]
    hyp = [U("speaker_1", 0, 1, "а в в"), U("speaker_1", 2, 3, "г")]
    assert cpwer(ref, hyp, "norm").counts.errors == wer(ref, hyp, "norm").errors == 2


@pytest.mark.parametrize("mode", ["raw", "norm", "norm_hy", "norm_num"])
def test_wer_identity(mode):
    ref = [U("speaker_1", 0, 1, "Кто-то 5 раз сказал: «мяу»!")]
    assert wer(ref, ref, mode).errors == 0


def test_rapidfuzz_and_python_agree():
    import random

    from stenoeval import wer as w

    rnd = random.Random(7)
    for _ in range(100):
        a = [rnd.choice("abcd") for _ in range(rnd.randint(0, 12))]
        b = [rnd.choice("abcd") for _ in range(rnd.randint(0, 12))]
        d = w._edit_distance_py(a, b)
        assert w.edit_distance(a, b) == d
        assert sum(o != "C" for o, _, _ in w._align_py(a, b)) == d
        assert sum(o != "C" for o, _, _ in w._align_rf(a, b)) == d
