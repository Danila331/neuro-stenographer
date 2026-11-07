from refpipe.asr import build_words, plain_word, spread_words
from refpipe.types import TimeSpan


def chars(text):
    return list(text)


def test_char_tokens_to_words_with_absolute_time():
    toks = list("да нет")
    ts = [0.00, 0.04, 0.08, 0.20, 0.24, 0.28]  # д а ' ' н е т
    w = build_words(toks, ts, None, TimeSpan(1000, 2000), tail_ms=40)
    assert [(x.text, x.start_ms, x.end_ms) for x in w] == [("да", 1000, 1080), ("нет", 1200, 1320)]


def test_end_is_clipped_by_next_word_start():
    toks = list("а б")
    ts = [0.00, 0.04, 0.06]
    w = build_words(toks, ts, None, TimeSpan(0, 1000), tail_ms=200)
    assert w[0].end_ms == 60 and w[1].start_ms == 60 and w[1].end_ms == 260


def test_bpe_pieces_with_underscore_boundary():
    toks = ["▁при", "вет", "▁мир"]
    ts = [0.0, 0.2, 0.6]
    w = build_words(toks, ts, None, TimeSpan(0, 5000), tail_ms=40)
    assert [(x.text, x.start_ms) for x in w] == [("привет", 0), ("мир", 600)]


def test_confidence_is_mean_probability():
    import math
    w = build_words(list("да"), [0.0, 0.04], [math.log(0.5), math.log(1.0)], TimeSpan(0, 1000))
    assert abs(w[0].confidence - 0.75) < 1e-9


def test_plain_mode_strips_punctuation_native_keeps_it():
    toks = list("Привет, мир!")
    ts = [i * 0.04 for i in range(len(toks))]
    plain = build_words(toks, ts, None, TimeSpan(0, 5000), keep_punct=False)
    native = build_words(toks, ts, None, TimeSpan(0, 5000), keep_punct=True)
    assert [w.text for w in plain] == ["привет", "мир"]
    assert [w.text for w in native] == ["Привет,", "мир!"]


def test_lone_punctuation_token_attaches_to_previous_word_in_native_mode():
    w = build_words(["▁да", "▁", ",", "▁нет"], [0.0, 0.1, 0.1, 0.5], None, TimeSpan(0, 2000), keep_punct=True)
    assert [x.text for x in w] == ["да,", "нет"]


def test_special_tokens_and_empty():
    assert build_words(["<unk>"], [0.0], None, TimeSpan(0, 10)) == []
    assert build_words([], [], None, TimeSpan(0, 10)) == []


def test_spread_words_covers_chunk_proportionally():
    w = spread_words("раз три", TimeSpan(1000, 2000))
    assert w[0].start_ms == 1000 and w[-1].end_ms <= 2000 and w[0].end_ms <= w[1].start_ms


def test_plain_word():
    assert plain_word("Привет,") == "привет" and plain_word("кто-то!") == "кто-то" and plain_word("—") == ""


def test_tdt_durations_replace_the_tail():
    w = build_words(list("да"), [0.0, 0.08], None, TimeSpan(0, 5000), tail_ms=40, durations=[0.08, 0.24])
    assert (w[0].start_ms, w[0].end_ms) == (0, 320)


def test_punctuation_tokens_do_not_move_word_boundaries():
    toks = [" Ни", "чь", "их", ",", " Что"]
    ts = [0.04, 0.16, 0.36, 0.60, 0.72]
    w = build_words(toks, ts, None, TimeSpan(0, 5000), tail_ms=40, keep_punct=True)
    assert [x.text for x in w] == ["Ничьих,", "Что"]
    assert w[0].end_ms == 400  # last LETTER token (0.36 s) + 40 ms, the comma at 0.60 s is ignored
    assert w[0].start_ms == 40


def test_start_offset_shifts_both_edges():
    w = build_words(list("да"), [0.2, 0.24], None, TimeSpan(1000, 5000), tail_ms=40, start_offset_ms=-30)
    assert (w[0].start_ms, w[0].end_ms) == (1170, 1250)
