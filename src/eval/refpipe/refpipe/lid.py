"""Stage LanguageId (ARCHITECTURE: after VAD): ru | en from the first ~10 s of speech.

Model: Whisper encoder/decoder through sherpa-onnx `SpokenLanguageIdentification` (e.g. whisper-tiny
multilingual).  `lid_model` is a directory with `*encoder*.onnx` and `*decoder*.onnx` (int8 preferred).
Decision rule: detected "en" -> en, anything else (ru, uk, bg, unknown, error) -> ru.
"""
from __future__ import annotations

import logging
from pathlib import Path

import numpy as np

from .types import TimeSpan

log = logging.getLogger(__name__)
SR = 16000


def first_speech(x: np.ndarray, speech: list[TimeSpan], seconds: float) -> np.ndarray:
    """Concatenated speech intervals from the start of the file, cut to `seconds`."""
    need = int(seconds * SR)
    parts, got = [], 0
    for s in speech:
        seg = x[s.start_ms * SR // 1000:s.end_ms * SR // 1000]
        parts.append(seg[: need - got])
        got += len(parts[-1])
        if got >= need:
            break
    return np.concatenate(parts) if parts else np.zeros(0, dtype=np.float32)


def decide(lang: str | None) -> str:
    return "en" if (lang or "").lower().startswith("en") else "ru"


class LanguageId:
    def __init__(self, lid_dir: Path, num_threads: int = 1, seconds: float = 10.0):
        import sherpa_onnx as so

        d = Path(lid_dir)

        def pick(kind: str) -> str:
            c = sorted(d.glob(f"*{kind}*.int8.onnx")) or sorted(d.glob(f"*{kind}*.onnx"))
            if not c:
                raise FileNotFoundError(f"no *{kind}*.onnx in {d}")
            return str(c[0])

        cfg = so.SpokenLanguageIdentificationConfig(
            whisper=so.SpokenLanguageIdentificationWhisperConfig(encoder=pick("encoder"), decoder=pick("decoder")),
            num_threads=num_threads)
        self.slid = so.SpokenLanguageIdentification(cfg)
        self.seconds = seconds

    def detect(self, x: np.ndarray, speech: list[TimeSpan]) -> tuple[str, str | None]:
        """Returns (ru|en, raw detected code) for the first `seconds` of speech."""
        return self.detect_audio(first_speech(x, speech, self.seconds))

    def detect_chunk(self, x: np.ndarray, chunk: TimeSpan, min_ms: int = 1500) -> tuple[str | None, str | None]:
        """LID of the first `seconds` of one chunk; (None, None) if the chunk is shorter than min_ms."""
        if chunk.dur_ms < min_ms:
            return None, None
        seg = x[chunk.start_ms * SR // 1000:(chunk.start_ms + int(self.seconds * 1000)) * SR // 1000]
        seg = seg[: min(len(seg), chunk.dur_ms * SR // 1000)]
        return self.detect_audio(seg)

    def detect_audio(self, seg: np.ndarray) -> tuple[str, str | None]:
        if len(seg) < SR // 2:
            return "ru", None
        try:
            st = self.slid.create_stream()
            st.accept_waveform(SR, seg)
            raw = self.slid.compute(st)
        except Exception as e:  # noqa: BLE001 -- LID must never break processing
            log.warning("language id failed: %s", e)
            return "ru", None
        return decide(raw), raw


def smooth_languages(raw: list[str | None], durs_ms: list[int], confident_ms: int = 6000) -> list[str]:
    """Hysteresis for per-chunk language ids -- 1:1 with Android `LanguageSmoother` (core/LanguageSmoother.kt).

    raw[i]: LID output of chunk i or None (chunk shorter than MIN_LID_MS = 1500 ms: not classified).  Each
    prediction is binarised: starts with "en" -> "en", anything else -> "ru".  The state starts at "ru" (no file-level
    LID in this mode).  A chunk with prediction p != state switches the state to p iff the NEXT chunk that has a
    prediction also says p, or the chunk lasts >= confident_ms; a lone outlier between chunks of one language is
    ignored.  Chunks without a prediction inherit the state.  Returns the language of every chunk."""
    binp = [None if r is None else ("en" if r.lower().startswith("en") else "ru") for r in raw]
    state = "ru"
    out: list[str] = []
    for i, p in enumerate(binp):
        if p is not None and p != state:
            nxt = next((q for q in binp[i + 1:] if q is not None), None)
            if nxt == p or durs_ms[i] >= confident_ms:
                state = p
        out.append(state)
    return out
