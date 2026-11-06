"""Stage VAD (Silero via sherpa-onnx) + ASR chunking.

Speech intervals (ms, ints):
  1. sherpa-onnx Silero VAD with threshold / min_silence / min_speech / max_speech from the config;
  2. each interval is padded by `pad_ms` on both sides (clamped to the file), overlapping or touching
     intervals are merged;
  3. ASR chunks: consecutive intervals are grouped while (last.end - first.start) <= chunk.max_s;
     the chunk is the contiguous audio slice [first.start, last.end] (inner pauses included), so a
     cut can only happen inside a pause between two intervals.  An interval longer than max_s on its
     own (pad can push a 25 s VAD segment over 25 s) is split at the quietest 20 ms frame in the
     second half of the allowed window (`split_long`).
"""
from __future__ import annotations

from pathlib import Path

import numpy as np

from .types import TimeSpan

SR = 16000
WINDOW = 512  # Silero v5 window at 16 kHz


def _ms(samples: int) -> int:
    return samples * 1000 // SR


class SileroVad:
    """Silero VAD through sherpa-onnx; the model is loaded once and reset() between files."""

    def __init__(self, vad_cfg: dict, models_root: Path, num_threads: int = 1):
        import sherpa_onnx as so

        silero = so.SileroVadModelConfig(
            model=str(models_root / vad_cfg["model"]),
            threshold=float(vad_cfg["threshold"]),
            min_silence_duration=vad_cfg["min_silence_ms"] / 1000.0,
            min_speech_duration=vad_cfg["min_speech_ms"] / 1000.0,
            window_size=WINDOW,
            max_speech_duration=float(vad_cfg["max_speech_s"]),
        )
        self.vad = so.VoiceActivityDetector(
            so.VadModelConfig(silero_vad=silero, sample_rate=SR, num_threads=num_threads),
            buffer_size_in_seconds=60.0)

    def detect(self, x: np.ndarray) -> list[TimeSpan]:
        """Raw Silero segments (ms), before padding."""
        vad = self.vad
        vad.reset()
        spans: list[TimeSpan] = []

        def drain():
            while not vad.empty():
                seg = vad.front
                spans.append(TimeSpan(_ms(seg.start), _ms(seg.start + len(seg.samples))))
                vad.pop()

        for i in range(0, len(x), WINDOW):
            vad.accept_waveform(x[i:i + WINDOW])
            drain()
        vad.flush()
        drain()
        return spans


def detect_speech(x: np.ndarray, vad_cfg: dict, models_root: Path, num_threads: int = 1) -> list[TimeSpan]:
    return SileroVad(vad_cfg, models_root, num_threads).detect(x)


def pad_and_merge(spans: list[TimeSpan], pad_ms: int, dur_ms: int) -> list[TimeSpan]:
    out: list[TimeSpan] = []
    for s in sorted(spans, key=lambda s: (s.start_ms, s.end_ms)):
        a, b = max(0, s.start_ms - pad_ms), min(dur_ms, s.end_ms + pad_ms)
        if b <= a:
            continue
        if out and a <= out[-1].end_ms:
            out[-1] = TimeSpan(out[-1].start_ms, max(out[-1].end_ms, b))
        else:
            out.append(TimeSpan(a, b))
    return out


def _quietest_cut(x: np.ndarray, lo_ms: int, hi_ms: int) -> int:
    """Centre (ms) of the quietest 20 ms frame fully inside [lo, hi]; earliest wins ties."""
    fr = 20
    best_t, best_e = (lo_ms + hi_ms) // 2, None
    t = lo_ms
    while t + fr <= hi_ms:
        seg = x[t * SR // 1000:(t + fr) * SR // 1000]
        e = float(np.dot(seg, seg)) if len(seg) else 0.0
        if best_e is None or e < best_e:
            best_t, best_e = t + fr // 2, e
        t += fr
    return best_t


def split_long(x: np.ndarray, span: TimeSpan, max_ms: int) -> list[TimeSpan]:
    out = []
    cur = span
    while cur.dur_ms > max_ms:
        cut = _quietest_cut(x, cur.start_ms + max_ms // 2, cur.start_ms + max_ms)
        out.append(TimeSpan(cur.start_ms, cut))
        cur = TimeSpan(cut, cur.end_ms)
    out.append(cur)
    return out


def make_chunks(x: np.ndarray, intervals: list[TimeSpan], max_s: float) -> list[TimeSpan]:
    max_ms = int(round(max_s * 1000))
    flat: list[TimeSpan] = []
    for iv in intervals:
        flat.extend(split_long(x, iv, max_ms) if iv.dur_ms > max_ms else [iv])
    chunks: list[TimeSpan] = []
    for iv in flat:
        if chunks and iv.end_ms - chunks[-1].start_ms <= max_ms:
            chunks[-1] = TimeSpan(chunks[-1].start_ms, iv.end_ms)
        else:
            chunks.append(iv)
    return chunks


def speech_chunks(x: np.ndarray, cfg: dict, vad: "SileroVad") -> tuple[list[TimeSpan], list[TimeSpan]]:
    """Returns (speech intervals after padding, ASR chunks)."""
    dur_ms = len(x) * 1000 // SR
    raw = vad.detect(x)
    iv = pad_and_merge(raw, int(cfg["vad"]["pad_ms"]), dur_ms)
    return iv, make_chunks(x, iv, float(cfg["asr"]["chunk"]["max_s"]))
