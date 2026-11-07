import json
from pathlib import Path

import pytest

pytest.importorskip("stenoeval")
from refpipe.postprocess import spell_numbers  # noqa: E402
from refpipe.types import Word  # noqa: E402

CASES = Path(__file__).resolve().parents[2] / ".." / "android" / "core" / "src" / "test" / "resources" / "num_cases.json"


def test_word_with_digits_splits_its_interval_evenly():
    out = spell_numbers([Word("25", 1000, 1600, 0.9), Word("котов", 1700, 2000)])
    assert [w.text for w in out] == ["двадцать", "пять", "котов"]
    assert (out[0].start_ms, out[0].end_ms, out[1].start_ms, out[1].end_ms) == (1000, 1300, 1300, 1600)
    assert out[0].confidence == 0.9


def test_single_word_result_keeps_the_interval():
    out = spell_numbers([Word("5", 0, 400)])
    assert [(w.text, w.start_ms, w.end_ms) for w in out] == [("пять", 0, 400)]


def test_android_number_cases_file_is_consistent_for_plain_integers():
    cases = json.loads(CASES.read_text(encoding="utf-8")) if CASES.is_file() else {}
    for k, v in cases.items():
        if k.isdigit():
            assert " ".join(w.text for w in spell_numbers([Word(k, 0, 1000)])) == v, k
