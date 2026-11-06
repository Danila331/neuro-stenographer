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
        """Returns (ru|en, raw detected code)."""
        seg = first_speech(x, speech, self.seconds)
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
