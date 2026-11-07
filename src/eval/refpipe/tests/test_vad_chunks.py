import numpy as np

from refpipe.vad import SR, pack_chunks, pad_segments


def S(a, b):  # seconds -> samples
    return int(a * SR), int(b * SR)


def test_padding_is_clamped_and_never_crosses_the_middle_of_a_pause():
    segs = [S(0.1, 0.5), S(0.6, 0.9), S(5.0, 5.2)]
    out = pad_segments(segs, 150, int(5.3 * SR))
    # first: lo clamped to 0; hi limited by the middle of the 0.5..0.6 pause
    assert out[0] == (0, (S(0.5, 0)[0] + S(0.6, 0)[0]) // 2)
    assert out[1][0] == out[0][1]  # neighbours touch at the pause middle, no overlap
    assert out[2] == (S(5.0, 0)[0] - 150 * 16, int(5.3 * SR))  # clamped to the file end


def test_pack_greedy_cut_only_between_segments():
    x = np.zeros(60 * SR, dtype=np.float32)
    segs = [S(0, 8), S(9, 17), S(18, 26), S(40, 45)]
    ch = pack_chunks(segs, 20.0, 1.0, x)
    assert ch[0] == S(0, 17)
    assert all(b - a <= 20 * SR for a, b in ch)
    starts = {a for a, _ in segs}
    ends = {b for _, b in segs}
    assert all(a in starts and b in ends for a, b in ch)


def test_overlong_segment_is_cut_at_the_quietest_place():
    x = (np.random.RandomState(0).randn(40 * SR) * 0.1).astype(np.float32)
    x[14 * SR:int(14.3 * SR)] = 0
    ch = pack_chunks([S(0, 30)], 20.0, 1.0, x)
    assert len(ch) == 2 and 14 * SR <= ch[0][1] <= int(14.3 * SR)
    assert ch[0][1] == ch[1][0] and ch[1][1] == 30 * SR


def test_short_tail_chunk_is_appended_to_the_previous_one():
    x = np.zeros(60 * SR, dtype=np.float32)
    ch = pack_chunks([S(0, 15), S(16, 30), S(31, 31.5)], 20.0, 1.0, x)
    # 16..30 | 31..31.5 -> too short -> merged (31.5-16 <= 24)
    assert ch == [S(0, 15), (S(16, 0)[0], S(31.5, 0)[0])]


def test_rescue_finds_energy_outside_covered_regions():
    from refpipe.vad import rescue_segments

    rng = np.random.RandomState(0)
    x = (rng.randn(20 * SR) * 0.001).astype(np.float32)           # noise floor ~ -60 dBFS
    x[2 * SR:4 * SR] += (rng.randn(2 * SR) * 0.05).astype(np.float32)   # burst A (covered by Silero)
    x[10 * SR:12 * SR] += (rng.randn(2 * SR) * 0.05).astype(np.float32)  # burst B (missed by Silero)
    out = rescue_segments(x, [(2 * SR, 4 * SR)], {"snr_db": 10, "abs_floor_db": -50})
    assert len(out) == 1 and abs(out[0][0] - 10 * SR) <= 0.1 * SR and abs(out[0][1] - 12 * SR) <= 0.1 * SR
    assert rescue_segments(np.zeros(5 * SR, dtype=np.float32), [], {}) == []
