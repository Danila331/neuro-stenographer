"""Аудио-утилиты: энергетический VAD, мощность, шум, реверберация, «телефон»."""
from __future__ import annotations

import os
import subprocess
import tempfile
from pathlib import Path

import numpy as np
import scipy.signal as ss
import soundfile as sf

from .common import SR

FRAME = 160  # 10 мс


def frame_db(x: np.ndarray) -> np.ndarray:
    n = len(x) // FRAME
    if n == 0:
        return np.full(1, -120.0)
    f = x[: n * FRAME].reshape(n, FRAME)
    return 10 * np.log10(np.mean(f.astype(np.float64) ** 2, axis=1) + 1e-12)


def speech_mask(x: np.ndarray, min_run: int = 3) -> np.ndarray:
    """Покадровая маска речи (10 мс) по энергии; порог адаптивный (пол шума / пик)."""
    e = frame_db(x)
    floor = np.percentile(e, 10)
    peak = np.percentile(e, 95)
    thr = max(floor + 9.0, peak - 32.0)
    m = e > thr
    # убрать «щелчки» короче min_run кадров и заполнить дыры короче 120 мс
    out = m.copy()
    i = 0
    n = len(m)
    while i < n:
        j = i
        while j < n and m[j] == m[i]:
            j += 1
        run = j - i
        if m[i] and run < min_run:
            out[i:j] = False
        i = j
    i = 0
    while i < n:
        j = i
        while j < n and out[j] == out[i]:
            j += 1
        if (not out[i]) and (j - i) < 12 and i > 0 and j < n:
            out[i:j] = True
        i = j
    return out


def trim_silence(x: np.ndarray, pad_ms: int = 10) -> tuple[np.ndarray, int, int]:
    """Обрезает тишину по краям. Возвращает (аудио, start_sample, end_sample) в исходных координатах."""
    m = speech_mask(x)
    idx = np.flatnonzero(m)
    if len(idx) == 0:
        return x, 0, len(x)
    pad = pad_ms * SR // 1000
    s = max(0, int(idx[0]) * FRAME - pad)
    e = min(len(x), (int(idx[-1]) + 1) * FRAME + pad)
    return x[s:e], s, e


def active_rms(x: np.ndarray) -> float:
    """RMS по речевым кадрам (для нормализации уровней и SNR)."""
    m = speech_mask(x)
    n = len(m)
    if n == 0 or not m.any():
        return float(np.sqrt(np.mean(x.astype(np.float64) ** 2) + 1e-12))
    f = x[: n * FRAME].reshape(n, FRAME)[m]
    return float(np.sqrt(np.mean(f.astype(np.float64) ** 2) + 1e-12))


def set_active_dbfs(x: np.ndarray, dbfs: float) -> np.ndarray:
    r = active_rms(x)
    g = 10 ** (dbfs / 20) / r
    return (x * g).astype(np.float32)


def fade(x: np.ndarray, ms: int = 8) -> np.ndarray:
    n = min(ms * SR // 1000, len(x) // 2)
    if n <= 0:
        return x
    y = x.copy()
    w = np.linspace(0, 1, n, dtype=np.float32)
    y[:n] *= w
    y[-n:] *= w[::-1]
    return y


def pink_noise(n: int, rng: np.random.Generator) -> np.ndarray:
    w = rng.standard_normal(n + 1024)
    b = [0.049922035, -0.095993537, 0.050612699, -0.004408786]
    a = [1, -2.494956002, 2.017265875, -0.522189400]
    y = ss.lfilter(b, a, w)[1024:]
    return (y / (np.std(y) + 1e-9)).astype(np.float32)


def mix_at_snr(speech: np.ndarray, noise: np.ndarray, snr_db: float) -> np.ndarray:
    """speech + noise при заданном SNR относительно активной речи."""
    if len(noise) < len(speech):
        reps = int(np.ceil(len(speech) / max(1, len(noise))))
        noise = np.tile(noise, reps)
    noise = noise[: len(speech)].astype(np.float64)
    sp = active_rms(speech)
    npow = np.sqrt(np.mean(noise ** 2) + 1e-12)
    g = sp / (10 ** (snr_db / 20)) / npow
    return (speech + g * noise).astype(np.float32)


def random_crop(x: np.ndarray, n: int, rng: np.random.Generator) -> np.ndarray:
    if len(x) <= n:
        reps = int(np.ceil(n / len(x)))
        x = np.tile(x, reps)
    s = int(rng.integers(0, len(x) - n + 1))
    return x[s: s + n]


def make_rir(rng: np.random.Generator, n_sources: int, rt60: float | None = None,
             rt60_range: tuple[float, float] = (0.25, 0.9), dist_range: tuple[float, float] = (0.6, 4.0)):
    """Синтетические RIR (pyroomacoustics, ShoeBox); у каждого источника своя позиция.

    Возвращает (list[np.ndarray] RIR с выровненным прямым путём, rt60, размеры комнаты, дистанции источник-микрофон).
    Прямой путь сдвинут в индекс 0 — реверберация не смещает метки времени.
    """
    import pyroomacoustics as pra
    dim = [float(rng.uniform(3.0, 9.0)), float(rng.uniform(3.0, 7.0)), float(rng.uniform(2.4, 3.5))]
    rt60 = rt60 or float(rng.uniform(*rt60_range))
    e_abs, max_order = pra.inverse_sabine(rt60, dim)
    max_order = min(max_order, 14)
    room = pra.ShoeBox(dim, fs=SR, materials=pra.Material(e_abs), max_order=max_order)
    mic = [float(rng.uniform(0.8, dim[0] - 0.8)), float(rng.uniform(0.8, dim[1] - 0.8)), float(rng.uniform(1.0, 1.6))]
    room.add_microphone(mic)
    positions = []
    for _ in range(n_sources):
        for _try in range(50):
            p = [float(rng.uniform(0.5, dim[0] - 0.5)), float(rng.uniform(0.5, dim[1] - 0.5)), float(rng.uniform(1.1, 1.8))]
            d = np.linalg.norm(np.array(p) - np.array(mic))
            if dist_range[0] < d < dist_range[1]:
                break
        room.add_source(p)
        positions.append(p)
    room.compute_rir()
    rirs = []
    dists = [float(np.linalg.norm(np.array(pos) - np.array(mic))) for pos in positions]
    for s in range(n_sources):
        h = np.asarray(room.rir[0][s], dtype=np.float64)
        k = int(np.argmax(np.abs(h)))
        h = h[k:]
        h = h[: int(SR * 1.2)]
        h = h / (np.sqrt(np.sum(h ** 2)) + 1e-9)  # единичная энергия — громкость сохраняется
        rirs.append(h)
    return rirs, rt60, dim, dists


def convolve(x: np.ndarray, h: np.ndarray) -> np.ndarray:
    return ss.fftconvolve(x, h, mode="full").astype(np.float32)


def ffmpeg_exe() -> str:
    import imageio_ffmpeg
    return os.environ.get("FFMPEG") or imageio_ffmpeg.get_ffmpeg_exe()


PHONE_CODECS = {
    "opus8": ["-c:a", "libopus", "-b:a", "8k", "-application", "voip", "-f", "ogg"],
    "opus12": ["-c:a", "libopus", "-b:a", "12k", "-application", "voip", "-f", "ogg"],
    "mp3_16": ["-c:a", "libmp3lame", "-b:a", "16k", "-f", "mp3"],
    "g726": ["-c:a", "g726", "-b:a", "24k", "-f", "wav"],
    "mulaw": ["-c:a", "pcm_mulaw", "-f", "wav"],
}


def phone_channel(x: np.ndarray, codec: str = "opus12") -> np.ndarray:
    """Полоса 300–3400 Гц, 8 кГц, кодек (ffmpeg), назад в 16 кГц. Длина и тайминг сохраняются."""
    import soxr
    sos = ss.butter(6, [300, 3400], btype="bandpass", fs=SR, output="sos")
    y = ss.sosfiltfilt(sos, x).astype(np.float32)
    y8 = soxr.resample(y, SR, 8000, quality="HQ")
    with tempfile.TemporaryDirectory(prefix="steno-phone-") as td:
        td = Path(td)
        sf.write(td / "in.wav", y8, 8000, subtype="PCM_16")
        enc = PHONE_CODECS[codec]
        ext = {"ogg": "ogg", "mp3": "mp3", "wav": "wav"}[enc[-1]]
        mid = td / f"mid.{ext}"
        out = td / "out.wav"
        ff = ffmpeg_exe()
        r1 = subprocess.run([ff, "-v", "error", "-y", "-i", str(td / "in.wav"), "-ar", "8000", "-ac", "1", *enc, str(mid)],
                            capture_output=True)
        r2 = subprocess.run([ff, "-v", "error", "-y", "-i", str(mid), "-ar", "8000", "-ac", "1", str(out)], capture_output=True)
        if r1.returncode or r2.returncode or not out.exists():
            raise RuntimeError(f"ffmpeg phone codec {codec} failed: {r1.stderr[-300:]!r} {r2.stderr[-300:]!r}")
        z, _ = sf.read(str(out), dtype="float32")
    # компенсация задержки кодека по кросс-корреляции (грубо, в пределах ±100 мс при 8 кГц)
    lag = _best_lag(y8, z, max_lag=800)
    if lag > 0:
        z = z[lag:]
    elif lag < 0:
        z = np.concatenate([np.zeros(-lag, np.float32), z])
    z = z[: len(y8)] if len(z) >= len(y8) else np.concatenate([z, np.zeros(len(y8) - len(z), np.float32)])
    out16 = soxr.resample(z.astype(np.float32), 8000, SR, quality="HQ")
    if len(out16) < len(x):
        out16 = np.concatenate([out16, np.zeros(len(x) - len(out16), np.float32)])
    return out16[: len(x)].astype(np.float32)


def _best_lag(a: np.ndarray, b: np.ndarray, max_lag: int) -> int:
    n = min(len(a), len(b), 8000 * 60)
    a_, b_ = a[:n].astype(np.float64), b[:n].astype(np.float64)
    c = ss.fftconvolve(b_, a_[::-1], mode="full")
    mid = len(a_) - 1
    lo, hi = max(0, mid - max_lag), min(len(c), mid + max_lag + 1)
    return int(np.argmax(c[lo:hi]) + lo - mid)


def hvac_noise(n: int, rng: np.random.Generator) -> np.ndarray:
    """Фон вентиляции/кондиционера: низкочастотный розовый шум + слабый тон ~100–120 Гц с медленной модуляцией."""
    pn = pink_noise(n, rng)
    sos = ss.butter(4, float(rng.uniform(1200, 3000)), btype="lowpass", fs=SR, output="sos")
    y = ss.sosfilt(sos, pn)
    t = np.arange(n) / SR
    f0 = float(rng.uniform(95, 125))
    mod = 1 + 0.3 * np.sin(2 * np.pi * float(rng.uniform(0.05, 0.3)) * t + float(rng.uniform(0, 6.28)))
    tone = 0.35 * np.sin(2 * np.pi * f0 * t) * mod + 0.15 * np.sin(2 * np.pi * 2 * f0 * t)
    y = y / (np.std(y) + 1e-9) + tone
    return (y / (np.std(y) + 1e-9)).astype(np.float32)


def time_stretch(x: np.ndarray, factor: float) -> np.ndarray:
    """Ускорение в `factor` раз без изменения тона (ffmpeg atempo, 0.5..2.0)."""
    factor = float(min(2.0, max(0.5, factor)))
    r = subprocess.run([ffmpeg_exe(), "-v", "error", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", "pipe:0",
                        "-filter:a", f"atempo={factor:.4f}", "-f", "f32le", "-ar", str(SR), "-ac", "1", "pipe:1"],
                       input=x.astype("<f4").tobytes(), capture_output=True)
    if r.returncode != 0 or not r.stdout:
        raise RuntimeError(f"ffmpeg atempo failed: {r.stderr[-200:]!r}")
    return np.frombuffer(r.stdout, dtype="<f4").astype(np.float32)
