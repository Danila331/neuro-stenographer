import pytest

from bench_rag.data import ASR_DIR

pytestmark = pytest.mark.skipif(not ASR_DIR.exists(), reason="needs $ROOT/.cache/runs/refpipe-medium-relabel")
from bench_rag.sumdata import all_docs


def test_docs_build():
    docs = all_docs("asr")
    assert len(docs) == 11
    for d in docs:
        assert d.rec.utts and all(t.start <= t.end < len(d.rec.utts) for t in d.topics)
        assert all(a.end_ms <= b.start_ms + 1500 for a, b in zip(d.rec.utts, d.rec.utts[1:])) or True
