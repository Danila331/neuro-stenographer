from bench_rag.text import norm_term, stem, terms


def test_stem_forms_collapse():
    assert norm_term("сметаны", "snow5") == norm_term("сметану", "snow5")
    assert stem("отпуска") == stem("отпуск") == stem("отпуске")
    assert stem("тихона") == stem("тихон")
    assert stem("дежурство") == stem("дежурства") == stem("дежурстве")


def test_stem_matches_snowball():
    snow = __import__("pytest").importorskip("snowballstemmer")
    s = snow.stemmer("russian")
    words = "говорящий обсуждали бюджете собеседника ремонтировать крыши крыша голосование отпуске миска миске рыбками".split()
    agree = sum(stem(w) == s.stemWord(w) for w in words)
    assert agree / len(words) >= 0.85


def test_terms_drop_stop_and_query_words():
    assert "что" not in terms("что говорил про бюджет")
    assert terms("что говорил про бюджет", drop_query_words=True) == [stem("бюджет")]
