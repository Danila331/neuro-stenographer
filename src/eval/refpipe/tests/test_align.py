import json
from pathlib import Path

import pytest

from refpipe.align import AlignParams, align, assign_speakers
from refpipe.types import SpeakerTurn, Word

CASES = json.loads((Path(__file__).parent / "align_cases.json").read_text())["cases"]


def run_case(c):
    words = [Word(w["text"], w["start_ms"], w["end_ms"]) for w in c["words"]]
    turns = [SpeakerTurn(t["speaker"], t["start_ms"], t["end_ms"]) for t in c["turns"]]
    p = c["params"]
    out = align(words, turns, AlignParams(p["max_gap_ms"], p["min_island_words"], p["min_island_ms"]))
    return [{"speaker_id": u.speaker_id, "start_ms": u.start_ms, "end_ms": u.end_ms, "text": u.text} for u in out]


@pytest.mark.parametrize("c", CASES, ids=[c["name"] for c in CASES])
def test_case(c):
    assert run_case(c) == c["expected"]


def test_cases_file_is_well_formed():
    names = [c["name"] for c in CASES]
    assert len(names) == len(set(names)) >= 20
    for c in CASES:
        assert set(c) == {"name", "description", "params", "words", "turns", "expected"}
        assert set(c["params"]) == {"max_gap_ms", "min_island_words", "min_island_ms"}


def test_words_text_is_preserved_in_order():
    for c in CASES:
        got = " ".join(u["text"] for u in run_case(c)).split()
        want = [w["text"] for w in sorted(c["words"], key=lambda w: (w["start_ms"], w["end_ms"]))]
        assert got == want


def test_outputs_are_sorted_and_valid():
    for c in CASES:
        out = run_case(c)
        keys = [(u["start_ms"], u["end_ms"]) for u in out]
        assert keys == sorted(keys)
        assert all(u["start_ms"] < u["end_ms"] for u in out)
        seen = []
        for u in out:  # numbering by first appearance
            n = int(u["speaker_id"].split("_")[1])
            if n not in seen:
                assert n == len(seen) + 1
                seen.append(n)


def test_assign_speakers_exposes_own_overlap():
    sp, own = assign_speakers([Word("а", 900, 1300)], [SpeakerTurn(0, 0, 1000), SpeakerTurn(1, 1000, 2000)])
    assert sp == [1] and own == [300]
