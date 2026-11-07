from bench_asr.textnorm import norm, wer, edit_counts


def test_norm():
    assert norm("Ёлка, Привет!  Мир—ёж") == "елка привет мир еж"


def test_wer_basic():
    assert wer(["а б в г"], ["а б в г"]) == 0
    assert wer(["а б в г"], ["а х в"]) == 0.5          # 1 sub + 1 del
    assert edit_counts(["а"], ["а", "б", "в"]) == (0, 0, 2)


def test_wer_modes():
    assert wer(["Привет, мир"], ["привет мир"], "norm") == 0
    assert wer(["Привет, мир"], ["привет мир"], "raw") == 0.5


def test_pc_modes():
    from bench_asr.textnorm import tokens
    assert tokens("Привет, Ёжик!", "pc") == ["Привет,", "Ежик!"]
    assert tokens("Привет, Ёжик!", "pc_sep") == ["Привет", ",", "Ежик", "!"]
    assert tokens("Нас 12.", "pc_sep_num") == ["Нас", "двенадцать", "."]
    assert wer(["Привет, мир!"], ["Привет, мир!"], "pc_sep") == 0
