"""Stage VAD (Silero via sherpa-onnx) + ASR chunking.

Speech intervals (ms, ints):
  1. sherpa-onnx Silero VAD with threshold / min_silence / min_speech / max_speech from the config;
  2. each interval is padded by `pad_ms` on both sides (clamped to the file); the padding never
     crosses the middle of the pause to a neighbour, intervals are NOT merged (as bench_asr/vad.py);
  3. ASR chunks (`pack_chunks`, ported from bench_asr/vad.py, in SAMPLES): padded intervals are
     packed greedily while (last.end - first.start) <= chunk.max_s; the chunk is the contiguous audio
     slice (inner pauses included) so a cut is only ever made inside a pause.  An interval longer than
     max_s is cut at the quietest point (30 ms RMS windows, 3-window smoothing) of
     [a + 0.55*max, a + max]; a chunk shorter than chunk.min_s is appended to the previous chunk if the
     result is <= 1.2*max_s.
"""
from __future__ import annotations

from pathlib import Path
from typing import NamedTuple

import numpy as np

from .types import TimeSpan

SR = 16000
WINDOW = 512  # Silero v5 window at 16 kHz


def _ms(samples: int) -> int:
    return samples * 1000 // SR


class Seg(NamedTuple):
    start_sample: int
    end_sample: int


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

    def detect_samples(self, x: np.ndarray) -> list["Seg"]:
        """Raw Silero segments in samples, before padding.  Whole 512-sample windows only (the tail
        shorter than one window is dropped), as in bench_asr/vad.py."""
        vad = self.vad
        vad.reset()
        spans: list[Seg] = []

        def drain():
            while not vad.empty():
                seg = vad.front
                spans.append(Seg(seg.start, seg.start + len(seg.samples)))
                vad.pop()

        for i in range(0, len(x) - WINDOW + 1, WINDOW):
            vad.accept_waveform(x[i:i + WINDOW])
            drain()
        vad.flush()
        drain()
        return spans

    def detect(self, x: np.ndarray) -> list[TimeSpan]:
        return [_span(s.start_sample, s.end_sample) for s in self.detect_samples(x)]


def detect_speech(x: np.ndarray, vad_cfg: dict, models_root: Path, num_threads: int = 1) -> list[TimeSpan]:
    return SileroVad(vad_cfg, models_root, num_threads).detect(x)


def pad_segments(segs: list[tuple[int, int]], pad_ms: int, n_samples: int) -> list[tuple[int, int]]:
    """Pad every VAD segment (sample indices) by pad_ms; the border between neighbours is the middle of the pause."""
    pad = int(pad_ms * SR / 1000)
    out = []
    for i, (a, b) in enumerate(segs):
        lo, hi = max(0, a - pad), min(n_samples, b + pad)
        if i > 0:
            lo = max(lo, (segs[i - 1][1] + a) // 2)
        if i + 1 < len(segs):
            hi = min(hi, (b + segs[i + 1][0]) // 2)
        out.append((lo, hi))
    return out


def _quiet_cut(x: np.ndarray, a: int, b: int, mx: int) -> int:
    lo, hi = a + int(0.55 * mx), min(b, a + mx)
    if hi <= lo:
        return hi
    w = int(0.03 * SR)
    seg = x[lo:hi]
    n = len(seg) // w
    if n < 4:
        return hi
    e = np.sqrt((seg[: n * w].reshape(n, w) ** 2).mean(1))
    e = np.convolve(e, np.ones(3) / 3, mode="same")
    return lo + int(np.argmin(e)) * w + w // 2


def pack_chunks(segs: list[tuple[int, int]], max_s: float, min_s: float, x: np.ndarray) -> list[tuple[int, int]]:
    if not segs:
        return []
    mx = int(max_s * SR)
    pieces = []
    for a, b in segs:
        while b - a > mx:
            c = _quiet_cut(x, a, b, mx)
            pieces.append((a, c))
            a = c
        pieces.append((a, b))
    chunks = [list(pieces[0])]
    for a, b in pieces[1:]:
        if b - chunks[-1][0] <= mx:
            chunks[-1][1] = b
        else:
            chunks.append([a, b])
    merged: list[tuple[int, int]] = []
    for a, b in chunks:
        if merged and (b - a) < min_s * SR and (b - merged[-1][0]) <= mx * 1.2:
            merged[-1] = (merged[-1][0], b)
        else:
            merged.append((a, b))
    return merged


def rescue_segments(x: np.ndarray, covered: list[tuple[int, int]], rcfg: dict) -> list[tuple[int, int]]:
    """EXPERIMENTAL (vad.rescue, refpipe only): energy-based speech candidates in regions Silero left empty.

    Some voices (quiet / codec-damaged synthetic phone speakers) get ~0 Silero probability even though GigaAM
    recognises them well.  20 ms frame RMS in dB; noise floor = 10th percentile; a frame is active if
    > max(floor + snr_db, abs_floor_db); active runs are bridged over gaps < bridge_ms and kept if
    >= min_ms; parts overlapping `covered` (+/- margin_ms) are removed; remaining pieces >= min_ms are returned
    (samples).  Their ASR output is filtered by postprocess.drop_tiny_chunks like any chunk."""
    fr = int(0.02 * SR)
    n = len(x) // fr
    if n < 10:
        return []
    rms = np.sqrt((x[: n * fr].reshape(n, fr) ** 2).mean(1))
    db = 20 * np.log10(np.maximum(rms, 1e-9))
    thr = max(float(np.percentile(db, 10)) + float(rcfg.get("snr_db", 12.0)), float(rcfg.get("abs_floor_db", -55.0)))
    act = db > thr
    bridge = int(rcfg.get("bridge_ms", 300) / 20)
    minf = int(rcfg.get("min_ms", 600) / 20)
    # bridge short gaps
    i = 0
    while i < n:
        if not act[i]:
            j = i
            while j < n and not act[j]:
                j += 1
            if 0 < i and j < n and j - i <= bridge:
                act[i:j] = True
            i = j
        else:
            i += 1
    cov = np.zeros(n, dtype=bool)
    m = int(rcfg.get("margin_ms", 200) / 20)
    for a, b in covered:
        cov[max(0, a // fr - m): min(n, b // fr + m + 1)] = True
    cand = act & ~cov
    out = []
    i = 0
    while i < n:
        if cand[i]:
            j = i
            while j < n and cand[j]:
                j += 1
            if j - i >= minf:
                out.append((i * fr, j * fr))
            i = j
        else:
            i += 1
    return out


def _span(a: int, b: int) -> TimeSpan:
    return TimeSpan(a * 1000 // SR, b * 1000 // SR)


def speech_segments(x: np.ndarray, cfg: dict, vad: "SileroVad") -> list[tuple[int, int]]:
    """Padded speech segments in samples (Silero + optional rescue + pad_segments)."""
    raw = [(s.start_sample, s.end_sample) for s in vad.detect_samples(x)]
    rc = cfg["vad"].get("rescue")
    if rc and rc.get("enabled"):
        raw = sorted(raw + rescue_segments(x, raw, rc))
    return pad_segments(raw, int(cfg["vad"]["pad_ms"]), len(x))


def chunks_from_segments(segs: list[tuple[int, int]], cfg: dict, x: np.ndarray) -> list[tuple[int, int]]:
    ch = cfg["asr"]["chunk"]
    return pack_chunks(segs, float(ch["max_s"]), float(ch.get("min_s", 0)), x)


def speech_chunks(x: np.ndarray, cfg: dict, vad: "SileroVad") -> tuple[list[TimeSpan], list[TimeSpan]]:
    """Returns (padded speech intervals, ASR chunks), both in ms."""
    seg = speech_segments(x, cfg, vad)
    chunks = chunks_from_segments(seg, cfg, x)
    return [_span(a, b) for a, b in seg if b > a], [_span(a, b) for a, b in chunks if b > a]
