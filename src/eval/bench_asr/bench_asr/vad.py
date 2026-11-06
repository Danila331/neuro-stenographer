"""Silero VAD (sherpa-onnx) с параметрами из config/tiers/*.json и упаковка сегментов в чанки (как в приложении)."""
from __future__ import annotations
import numpy as np
import sherpa_onnx
from .engines import MODELS

SR = 16000


def vad_segments(x: np.ndarray, threshold=0.45, min_silence=0.3, min_speech=0.12, max_speech=25.0, pad_ms=150,
                 model=None) -> list[tuple[int, int]]:
    """Возвращает [(start_sample, end_sample)] речевых интервалов (с паддингом)."""
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = str(model or MODELS / "silero-vad" / "silero_vad.onnx")
    cfg.silero_vad.threshold = threshold
    cfg.silero_vad.min_silence_duration = min_silence
    cfg.silero_vad.min_speech_duration = min_speech
    cfg.silero_vad.max_speech_duration = max_speech
    cfg.sample_rate = SR
    vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=60)
    win = cfg.silero_vad.window_size
    for i in range(0, len(x) - win + 1, win):
        vad.accept_waveform(x[i:i + win])
    vad.flush()
    segs = []
    while not vad.empty():
        s = vad.front
        segs.append((s.start, s.start + len(s.samples)))
        vad.pop()
    # паддинг не должен перекрывать соседей: между близкими сегментами граница — середина паузы; сегменты НЕ склеиваем
    # (sherpa уже режет по max_speech; склейка + жёсткая нарезка рубила слова — см. results/README)
    pad = int(pad_ms * SR / 1000)
    out = []
    for i, (a, b) in enumerate(segs):
        lo = max(0, a - pad); hi = min(len(x), b + pad)
        if i > 0:
            lo = max(lo, (segs[i - 1][1] + a) // 2)
        if i + 1 < len(segs):
            hi = min(hi, (b + segs[i + 1][0]) // 2)
        out.append((lo, hi))
    return out


def _quiet_cut(x: np.ndarray, a: int, b: int, mx: int) -> int:
    """Точка разреза в [a+0.55*mx, a+mx] с минимальной энергией (окно 30 мс, сглаживание ~0.1 с)."""
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


def pack_chunks(segs: list[tuple[int, int]], max_s: float, min_s: float = 1.0, x: np.ndarray | None = None) -> list[tuple[int, int]]:
    """Жадно склеивает соседние интервалы в чанки <= max_s (паузы внутри сохраняются). Интервалы длиннее max_s режутся
    по самому тихому месту (если передан x), иначе жёстко."""
    if not segs:
        return []
    mx = int(max_s * SR)
    pieces = []
    for a, b in segs:
        while b - a > mx:
            c = _quiet_cut(x, a, b, mx) if x is not None else a + mx
            pieces.append((a, c)); a = c
        pieces.append((a, b))
    chunks = [list(pieces[0])]
    for a, b in pieces[1:]:
        if b - chunks[-1][0] <= mx:
            chunks[-1][1] = b
        else:
            chunks.append([a, b])
    merged = []
    for a, b in chunks:
        if merged and (b - a) < min_s * SR and (b - merged[-1][0]) <= mx * 1.2:
            merged[-1] = (merged[-1][0], b)
        else:
            merged.append((a, b))
    return [(a, b) for a, b in merged]
