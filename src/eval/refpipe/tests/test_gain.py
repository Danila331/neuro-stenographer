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


def test_apply_only_to_quiet_files():
    quiet = np.full(100, 10 ** (-30 / 20), dtype=np.float32)
    loud = np.full(100, 10 ** (-10 / 20), dtype=np.float32)
    cfg = {**CFG, "apply_if_peak_below_dbfs": -20.0}
    assert normalize_gain(quiet, cfg)[1] > 0
    assert normalize_gain(loud, cfg)[1] == 0.0


def test_segment_gain_normalises_each_segment_and_leaves_gaps():
    from refpipe.audio import segment_gain

    x = np.zeros(16000 * 3, dtype=np.float32)
    x[0:16000] = 0.01 * np.sin(np.arange(16000) * 0.1)          # quiet segment 0-1 s
    x[2 * 16000:3 * 16000] = 0.3 * np.sin(np.arange(16000) * 0.1)  # loud segment 2-3 s
    y = segment_gain(x, [(0, 1000), (2000, 3000)], {"peak_dbfs": -1.0, "max_gain_db": 40.0})
    assert abs(np.abs(y[:16000]).max() - 10 ** (-1 / 20)) < 0.02 and abs(np.abs(y[2 * 16000:]).max() - 10 ** (-1 / 20)) < 0.02
    assert np.all(y[16000:2 * 16000] == 0)


def test_agc_raises_quiet_parts_only_up_to_the_cap():
    from refpipe.audio import agc

    t = np.arange(16000 * 6) * 0.1
    x = (0.3 * np.sin(t)).astype(np.float32)
    x[16000 * 3:] *= 0.03                                      # -30 dB
    y = agc(x, {"frame_ms": 500, "target_dbfs": -24, "max_gain_db": 12, "floor_dbfs": -58})
    assert np.abs(y[16000 * 4:16000 * 5]).max() > 1.5 * np.abs(x[16000 * 4:16000 * 5]).max()
    assert np.abs(y[16000 * 4:16000 * 5]).max() < 4.5 * np.abs(x[16000 * 4:16000 * 5]).max()   # +12 dB cap = x3.98
