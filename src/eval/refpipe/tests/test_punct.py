import json
from pathlib import Path

import pytest

from refpipe.punct import fallback_text, guard_text, norm, make_punctuator, NonePunctuator
from refpipe.types import Utterance

G = json.loads((Path(__file__).parent / "guard_cases.json").read_text())


@pytest.mark.parametrize("c", G["norm"])
def test_norm(c):
    assert norm(c["in"]) == c["out"]


@pytest.mark.parametrize("c", G["guard"])
def test_guard(c):
    assert guard_text(c["input"], c["output"]) == (c["text"], c["accepted"])


def test_fallback_idempotent_on_punctuated():
    assert fallback_text("Привет.") == "Привет."


def test_llm_source_falls_back_when_model_missing(tmp_path):
    p, src = make_punctuator({"punctuation": {"source": "llm"}, "llm": {"model": "llm/nope.gguf"}}, tmp_path)
    assert isinstance(p, NonePunctuator) and src.startswith("none")
    u = [Utterance(1, 0, 10, "привет", [])]
    assert p.punctuate(u)[0].text == "привет"
