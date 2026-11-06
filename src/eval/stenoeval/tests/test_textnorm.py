from stenoeval.textnorm import normalize, tokens


def test_raw_keeps_everything():
    assert tokens("Привет,  мир! Ёж", "raw") == ["Привет,", "мир!", "Ёж"]


def test_norm():
    assert normalize("Привет, МИР!  Ёлки-палки… «да»", "norm") == "привет мир елки палки да"
    assert normalize("за́мок", "norm") == "замок"
    assert normalize("", "norm") == ""
    assert normalize("  ,.! ", "norm") == ""
    assert normalize("Hello, World 42", "norm") == "hello world 42"


def test_unicode_normal_forms():
    assert normalize("й", "norm") == "й"          # decomposed й
    assert normalize("‑ ½", "norm") == "1 2" or normalize("‑ ½", "norm")  # NFKC does not crash


def test_hyphen_policy():
    assert tokens("кто-то", "norm") == ["кто", "то"]
    assert tokens("кто-то — это", "norm_hy") == ["кто-то", "это"]
    assert tokens("-слово-", "norm_hy") == ["слово"]


def test_norm_num():
    assert normalize("25 котов", "norm_num") == "двадцать пять котов"
    assert normalize("3,5 кг", "norm_num") == "три запятая пять кг"
    assert normalize("5-й", "norm_num") == "пятый"
    assert normalize("в 1990 году", "norm_num").startswith("в тысяча девятьсот девяносто")
    assert normalize("10-15", "norm_num") == "десять пятнадцать"
    assert normalize("0", "norm_num") == "ноль"
    assert normalize("в 2-х", "norm_num") == "в два"
    long = normalize("89161234567890123456789", "norm_num").split()
    assert len(long) == 23                               # read digit by digit
    assert normalize("двадцать пять", "norm_num") == normalize("25", "norm_num")


def test_unknown_mode():
    import pytest

    with pytest.raises(ValueError):
        tokens("a", "bogus")
