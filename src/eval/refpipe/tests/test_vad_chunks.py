import numpy as np

from refpipe.types import TimeSpan
from refpipe.vad import make_chunks, pad_and_merge, split_long

SR = 16000


def test_pad_and_merge_clamps_and_merges():
    out = pad_and_merge([TimeSpan(100, 500), TimeSpan(600, 900), TimeSpan(5000, 5200)], 150, 5300)
    assert out == [TimeSpan(0, 1050), TimeSpan(4850, 5300)]


def test_chunks_group_until_max_and_cut_only_in_pauses():
    x = np.zeros(60 * SR, dtype=np.float32)
    iv = [TimeSpan(0, 8000), TimeSpan(9000, 17000), TimeSpan(18000, 26000), TimeSpan(40000, 45000)]
    ch = make_chunks(x, iv, 25.0)
    assert ch[0] == TimeSpan(0, 17000)  # 0..26000 > 25000 so the third interval starts a new chunk
    assert ch[1] == TimeSpan(18000, 26000)  # 18000..45000 > 25000
    assert ch[2] == TimeSpan(40000, 45000)
    for c in ch:
        assert c.dur_ms <= 25000
        assert any(c.start_ms == i.start_ms for i in iv) and any(c.end_ms == i.end_ms for i in iv)


def test_overlong_interval_is_split_at_the_quietest_frame():
    x = (np.random.RandomState(0).randn(40 * SR) * 0.1).astype(np.float32)
    x[19 * SR:int(19.1 * SR)] = 0  # silence inside the second half of the allowed window
    parts = split_long(x, TimeSpan(0, 30000), 25000)
    assert len(parts) == 2 and 18900 <= parts[0].end_ms <= 19200
    assert parts[0].end_ms == parts[1].start_ms and parts[1].end_ms == 30000
    assert all(p.dur_ms <= 25000 for p in make_chunks(x, [TimeSpan(0, 30000)], 25.0))
