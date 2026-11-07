from refpipe.second_pass import merge_second_pass
from refpipe.types import Word


def W(t, s, e, c=0.5):
    return Word(t, s, e, c)


def test_equal_words_keep_first_pass_timing_and_replacements_fill_the_span():
    first = [W("привет", 0, 400), W("ма", 500, 700), W("как", 800, 1000), W("дела", 1100, 1400)]
    out = merge_second_pass(first, ["Привет,", "Мир", "как", "дела"])
    assert [w.text for w in out] == ["привет", "мир", "как", "дела"]
    assert (out[0].start_ms, out[0].end_ms) == (0, 400) and out[0].confidence == 0.5
    assert out[1].start_ms == 500 and out[1].end_ms <= 700 and out[1].confidence is None
    assert (out[3].start_ms, out[3].end_ms) == (1100, 1400)


def test_inserted_word_goes_into_the_gap_and_time_stays_monotonic():
    first = [W("да", 0, 200), W("нет", 1000, 1300)]
    out = merge_second_pass(first, ["да", "ну", "нет"])
    assert [w.text for w in out] == ["да", "ну", "нет"]
    assert 200 <= out[1].start_ms < out[1].end_ms <= 1000
    assert all(a.start_ms <= b.start_ms for a, b in zip(out, out[1:]))


def test_dropped_word():
    out = merge_second_pass([W("а", 0, 100), W("б", 200, 300)], ["а"])
    assert [w.text for w in out] == ["а"]
