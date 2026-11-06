from bench_asr.words import words_from_tokens, align_pairs, utterance_bounds_error


def test_words_char_level():
    toks = list("да нет")
    ts = [0.0, 0.04, 0.08, 0.2, 0.24, 0.28]
    w = words_from_tokens(toks, ts)
    assert [x[0] for x in w] == ["да", "нет"]
    assert w[1][1] == 0.2 and abs(w[1][2] - 0.32) < 1e-9


def test_words_bpe():
    w = words_from_tokens(["▁при", "вет", "▁мир"], [0.1, 0.2, 0.5])
    assert [x[0] for x in w] == ["привет", "мир"]


def test_bounds_error():
    ref = [{"text": "привет мир", "start_ms": 1000, "end_ms": 2000}]
    hyp = [("привет", 1.1, 1.4), ("мир", 1.6, 2.2)]
    (es, ee), = utterance_bounds_error(ref, hyp)
    assert abs(es - 0.1) < 1e-9 and abs(ee - 0.2) < 1e-9
