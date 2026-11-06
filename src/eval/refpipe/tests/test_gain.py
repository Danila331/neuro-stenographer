import numpy as np

from refpipe.audio import normalize_gain

CFG = {"enabled": True, "mode": "peak", "peak_dbfs": -1.0, "max_gain_db": 40.0, "skip_if_peak_below_dbfs": -85.0}


def peak_db(x):
    return 20 * np.log10(np.abs(x).max())


def test_quiet_audio_is_raised_to_minus_one_dbfs():
    x = (np.random.RandomState(1).randn(16000) * 0.002).astype(np.float32)
    x /= np.abs(x).max() / (10 ** (-44 / 20))  # peak -44 dBFS
    y, g = normalize_gain(x, CFG)
    assert g == 40.0 and abs(peak_db(y) - (-4.0)) < 0.05  # -44 dBFS needs +43 dB, capped at +40


def test_gain_is_capped_and_digital_silence_is_untouched():
    x = np.full(1000, 1e-5, dtype=np.float32)  # -100 dBFS
    assert normalize_gain(x, CFG)[1] == 0.0
    z = np.full(1000, 10 ** (-60 / 20), dtype=np.float32)
    y, g = normalize_gain(z, CFG)
    assert g == 40.0


def test_loud_audio_is_attenuated_at_most_6_db_and_disabled_is_identity():
    x = np.array([0.0, 1.0, -1.0], dtype=np.float32)
    y, g = normalize_gain(x, CFG)
    assert abs(g + 1.0) < 1e-6
    assert normalize_gain(x, {**CFG, "enabled": False})[1] == 0.0
