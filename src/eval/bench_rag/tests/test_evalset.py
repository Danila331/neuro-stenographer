import pytest

from bench_rag.data import ASR_DIR

pytestmark = pytest.mark.skipif(not ASR_DIR.exists(), reason="needs $ROOT/.cache/runs/refpipe-medium-relabel")
from bench_rag.evalset import build_questions


def test_gold_resolves_ref_and_asr():
    for kind in ("ref", "asr"):
        corpus, qs = build_questions(kind)
        assert 40 <= len(qs) <= 60
        assert len(corpus) == 40
        empty = [q.id for q in qs if not q.gold]  # ASR lost the answer (allowed for the pipeline corpus only)
        assert len(empty) <= (0 if kind == 'ref' else 4), empty
