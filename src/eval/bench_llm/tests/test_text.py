from bench_llm.text import norm, guard_ok, parse, render, fallback, score, total
from bench_llm.punct import words_grammar, compact_out, parse_compact, fix_sentence_case
from bench_llm.diarfix import tagged, parse_tagged, sentence_snap, window_grammar, boundaries
from bench_llm.data import load_dialogs, inject_boundary_errors, load_fleurs


def test_norm_and_guard():
    assert norm("Ёлка,  ЕСТЬ!") == "елка есть"
    assert guard_ok("привет как дела", "Привет, как дела?")
    assert not guard_ok("привет как дела", "Привет, как твои дела?")
    assert not guard_ok("привет как дела", "Привет как")


def test_parse_marks_and_caps():
    t = parse("Москва — столица России, а Мурка живёт в Котовице!")
    assert [x.w for x in t] == "москва столица россии а мурка живет в котовице".split()
    assert t[0].mark == "—" and t[2].mark == "," and t[-1].mark == "!"
    assert [x.cap for x in t] == [True, False, True, False, True, False, False, True]
    # hyphenated words are split, quotes/colons are ignored
    t = parse("Кто-то сказал: «да».")
    assert [x.w for x in t] == ["кто", "то", "сказал", "да"]
    assert t[-1].mark == "." and t[2].mark == ""


def test_fallback_and_score():
    gold = parse("Привет, как дела?")
    fb = fallback([x.w for x in gold])
    assert render(fb) == "Привет как дела."
    c = total([score(gold, gold)])
    assert c["marks"].f1 == 1.0 and c["caps"].f1 == 1.0
    c = total([score(gold, fb)])
    assert c["marks"].tp == 0 and c["caps"].f1 == 1.0


def test_grammar_and_compact_roundtrip():
    words = "москва столица россии".split()
    g = words_grammar(words)
    assert '("москва" | "Москва")' in g and g.count("mark") >= 4
    toks = parse("Москва — столица России.")
    assert render(parse_compact(words, compact_out(toks))) == "москва — столица России."
    assert render(fix_sentence_case(parse_compact(words, compact_out(toks)))) == "Москва — столица России."


def test_diarfix_helpers():
    w = "ты придешь да приду".split()
    lab = [1, 1, 2, 2]
    s = tagged(w, lab)
    assert s == "[1] ты придешь [2] да приду"
    assert parse_tagged(s) == (w, lab)
    assert boundaries(lab) == [2]
    marks = ["", "?", "", "."]
    assert sentence_snap(marks, [1, 1, 1, 2]) == [1, 1, 2, 2]
    assert sentence_snap(["", "?", "", "."], [1, 1, 1, 2], 4) == [1, 1, 2, 2]
    assert 'root ::=' in window_grammar(w, 2)


def test_data():
    d = load_dialogs()
    assert len(d) == 5 and all(len(x.turns) >= 10 for x in d)
    words, gold, noisy = inject_boundary_errors(d[0], seed=0)
    assert len(words) == len(gold) == len(noisy) and gold != noisy
    assert len(load_fleurs("test", 10)) == 10
