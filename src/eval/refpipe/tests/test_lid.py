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


def test_smooth_languages_hysteresis():
    from refpipe.lid import smooth_languages

    # one short noisy 'en' between ru chunks is ignored
    assert smooth_languages(["ru", "en", "ru", "ru"], [5000, 2000, 5000, 5000]) == ["ru"] * 4
    # two consecutive en decisions switch (also for short chunks), and the return to ru needs the same
    assert smooth_languages(["ru", "en", "en", "ru", "ru"], [5000, 2000, 2000, 3000, 3000]) == ["ru", "en", "en", "ru", "ru"]
    # a single but long (>= 4 s) en chunk switches
    assert smooth_languages(["ru", "en", "ru"], [5000, 6000, 5000]) == ["ru", "en", "ru"]
    # chunks without a decision inherit the state; the first decision is taken as is
    assert smooth_languages([None, "en", None, "en"], [500, 9000, 500, 9000]) == ["en", "en", "en", "en"]
    assert smooth_languages([None, None], [500, 500]) == ["ru", "ru"]
