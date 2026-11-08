import numpy as np

from refpipe.lid import adaptive_lid_chunks
from refpipe.types import TimeSpan


class FakeLid:
    def __init__(self, mixed=True):
        self.mixed = mixed

    def detect_chunk(self, x, chunk):
        if chunk.dur_ms < 1500:
            return None, None
        if not self.mixed:
            return "ru", None
        return ("en" if chunk.start_ms < 6000 else "ru"), None


def test_mixed_chunk_splits_only_at_language_change():
    chunks, raw = adaptive_lid_chunks(np.zeros(12 * 16000, dtype=np.float32), [TimeSpan(0, 12000)], FakeLid(), max_s=5.0)
    assert [(c.start_ms, c.end_ms) for c in chunks] == [(0, 5000), (5000, 10000), (10000, 12000)]
    assert raw == ["en", "en", "ru"]


def test_single_language_chunk_preserves_original_boundaries():
    original = TimeSpan(0, 12000)
    chunks, raw = adaptive_lid_chunks(np.zeros(12 * 16000, dtype=np.float32), [original], FakeLid(mixed=False), max_s=5.0)
    assert chunks == [original]
    assert raw == ["ru"]


def test_short_english_tail_does_not_force_split():
    original = TimeSpan(0, 5300)
    chunks, raw = adaptive_lid_chunks(np.zeros(6 * 16000, dtype=np.float32), [original], FakeLid(), max_s=5.0)
    assert chunks == [original]
    assert raw == ["en"]


def test_empty_input_is_safe():
    assert adaptive_lid_chunks(np.zeros(0, dtype=np.float32), [], FakeLid()) == ([], [])


def test_vad_subchunks_confirm_english_without_splitting_single_language_asr():
    from refpipe.lid import adaptive_lid_plan

    class EnglishLid:
        def detect_chunk(self, x, chunk):
            return ("en" if chunk.dur_ms >= 1500 else None), None

    original = TimeSpan(0, 5320)
    fine = [TimeSpan(0, 3035), TimeSpan(3035, 5320)]
    chunks, raw, languages = adaptive_lid_plan(np.zeros(6 * 16000, dtype=np.float32), [original], fine, EnglishLid())
    assert chunks == [original]
    assert raw == ["en"]
    assert languages == ["en"]


def test_vad_subchunks_split_mixed_asr_at_speech_boundaries():
    from refpipe.lid import adaptive_lid_plan

    original = TimeSpan(0, 12875)
    fine = [TimeSpan(0, 3035), TimeSpan(3035, 5514), TimeSpan(6226, 9861), TimeSpan(9861, 12875)]
    chunks, raw, languages = adaptive_lid_plan(
        np.zeros(13 * 16000, dtype=np.float32), [original], fine, FakeLid())
    assert chunks == fine
    assert raw == ["en", "en", "ru", "ru"]
    assert languages == ["en", "en", "ru", "ru"]


def test_pipeline_splits_mixed_chunk_before_asr(monkeypatch, tmp_path):
    from refpipe.config import DEFAULTS, deep_merge
    from refpipe.pipeline import Pipeline
    from refpipe.types import Word

    calls = {"ru": [], "en": []}

    class FakeAsr:
        def __init__(self, cfg, root, threads):
            self.lang = "en" if cfg.get("model_dir") == "en" else "ru"

        def transcribe(self, x, chunks, **kwargs):
            calls[self.lang].extend((c.start_ms, c.end_ms) for c in chunks)
            return [Word(self.lang, c.start_ms, c.start_ms + 100) for c in chunks]

    class PipelineLid:
        def __init__(self, *args, **kwargs):
            pass

        def detect_chunk(self, x, chunk):
            return ("en" if chunk.start_ms < 10000 else "ru"), None

    monkeypatch.setattr("refpipe.pipeline.SileroVad", lambda *args: object())
    monkeypatch.setattr("refpipe.pipeline.AsrEngine", FakeAsr)
    monkeypatch.setattr("refpipe.pipeline.LanguageId", PipelineLid)
    monkeypatch.setattr("refpipe.pipeline.speech_chunks",
                        lambda *args: ([TimeSpan(0, 20000)], [TimeSpan(0, 20000)]))
    monkeypatch.setattr("refpipe.pipeline.speech_chunks_with_lid", lambda *args: (
        [TimeSpan(0, 20000)], [TimeSpan(0, 20000)],
        [TimeSpan(0, 5000), TimeSpan(5000, 10000), TimeSpan(10000, 15000), TimeSpan(15000, 20000)]))
    cfg = deep_merge(DEFAULTS, {
        "diarization": {"enabled": False},
        "language": {"mode": "auto", "per_chunk": True, "adaptive_lid": True, "lid_model": "lid"},
        "asr_en": {"model_dir": "en"},
    })
    result = Pipeline(cfg, tmp_path).process_audio(np.zeros(20 * 16000, dtype=np.float32))

    assert calls["en"] == [(0, 5000), (5000, 10000)]
    assert calls["ru"] == [(10000, 15000), (15000, 20000)]
    assert result.info["language_by_chunk"] == "eerr"
