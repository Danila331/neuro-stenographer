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


def test_smooth_languages_matches_android_language_smoother():
    from refpipe.lid import smooth_languages

    # a lone short 'en' outlier between ru chunks is ignored
    assert smooth_languages(["ru", "en", "ru", "ru"], [5000, 2000, 5000, 5000]) == ["ru"] * 4
    # two consecutive en decisions switch (also for short chunks); the way back needs the same confirmation
    assert smooth_languages(["ru", "en", "en", "ru", "ru"], [5000, 2000, 2000, 3000, 3000]) == ["ru", "en", "en", "ru", "ru"]
    # a single long (>= 6 s) en chunk switches, 5.9 s does not
    assert smooth_languages(["ru", "en", "ru"], [5000, 6000, 5000]) == ["ru", "en", "en"]  # the last 'ru' (5 s, unconfirmed) does not switch back
    assert smooth_languages(["ru", "en", "ru"], [5000, 6000, 6000]) == ["ru", "en", "ru"]
    assert smooth_languages(["ru", "en", "ru"], [5000, 5900, 5000]) == ["ru", "ru", "ru"]
    # the state starts at ru: an English first chunk needs confirmation / length as well
    assert smooth_languages(["en", "ru", "ru"], [3000, 3000, 3000]) == ["ru", "ru", "ru"]
    assert smooth_languages(["en", "en", "ru"], [3000, 3000, 3000]) == ["en", "en", "en"]
    # chunks without prediction inherit the state; 'en-US' counts as en; other codes as ru
    assert smooth_languages([None, "en", None, "en-US"], [500, 9000, 500, 9000]) == ["ru", "en", "en", "en"]
    assert smooth_languages(["uk", "bg"], [9000, 9000]) == ["ru", "ru"]
