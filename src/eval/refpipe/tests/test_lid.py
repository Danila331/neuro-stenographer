import numpy as np

from refpipe.lid import decide, first_speech
from refpipe.types import TimeSpan

SR = 16000


def test_decide_only_english_switches():
    assert decide("en") == "en" and decide("EN") == "en"
    assert all(decide(x) == "ru" for x in ("ru", "uk", "bg", "", None, "zh"))


def test_first_speech_concatenates_intervals_and_cuts():
    x = np.arange(30 * SR, dtype=np.float32)
    seg = first_speech(x, [TimeSpan(1000, 4000), TimeSpan(10000, 20000)], 5.0)
    assert len(seg) == 5 * SR
    assert seg[0] == 1000 * SR // 1000 and seg[3 * SR] == 10 * SR  # second interval follows the first
    assert len(first_speech(x, [], 5.0)) == 0
