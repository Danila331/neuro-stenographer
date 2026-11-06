"""Stage DECODE (+ optional DENOISE): file -> mono 16 kHz float32 in [-1, 1]."""
from __future__ import annotations

import math
import shutil
import subprocess
import tempfile
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 16000
SF_NATIVE = {".wav", ".flac", ".ogg", ".oga", ".opus", ".mp3", ".aiff", ".aif", ".w64", ".caf"}


class AudioDecodeError(RuntimeError):
    pass


def _read_sf(path: Path) -> tuple[np.ndarray, int]:
    data, sr = sf.read(str(path), dtype="float32", always_2d=True)
    return data, sr


def _read_ffmpeg(path: Path) -> tuple[np.ndarray, int]:
    """Decode through ffmpeg (or macOS afconvert) into a temp wav, keeping the native layout."""
    with tempfile.TemporaryDirectory(prefix="refpipe-dec-") as td:
        out = Path(td) / "out.wav"
        ff = shutil.which("ffmpeg")
        if ff:
            cmd = [ff, "-nostdin", "-v", "error", "-i", str(path), "-vn", "-acodec", "pcm_f32le", str(out)]
        elif shutil.which("afconvert"):
            cmd = ["afconvert", "-f", "WAVE", "-d", "LEF32", str(path), str(out)]
        else:
            raise AudioDecodeError(
                f"cannot decode {path.suffix} without ffmpeg: install ffmpeg (brew install ffmpeg) "
                "or convert the file to wav/flac/ogg/mp3")
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0 or not out.exists():
            raise AudioDecodeError(f"decoder failed for {path}: {r.stderr.strip()[:300]}")
        return _read_sf(out)


def resample(x: np.ndarray, sr: int, target: int = SR) -> np.ndarray:
    if sr == target:
        return x
    try:
        import soxr

        return soxr.resample(x, sr, target, quality="VHQ").astype(np.float32, copy=False)
    except ImportError:  # pragma: no cover
        from scipy.signal import resample_poly

        g = math.gcd(sr, target)
        return resample_poly(x, target // g, sr // g).astype(np.float32)


def decode_audio(path: str | Path, target_sr: int = SR) -> np.ndarray:
    """wav/flac/ogg/mp3/opus via libsndfile; everything else (m4a, aac, mp4, ...) via ffmpeg.
    Channels are averaged, the result is resampled to `target_sr` with a VHQ soxr filter."""
    path = Path(path)
    if not path.is_file():
        raise AudioDecodeError(f"file not found: {path}")
    data = sr = None
    if path.suffix.lower() in SF_NATIVE:
        try:
            data, sr = _read_sf(path)
        except Exception:  # unsupported codec inside the container -> try ffmpeg
            data = None
    if data is None:
        data, sr = _read_ffmpeg(path)
    x = data.mean(axis=1) if data.shape[1] > 1 else data[:, 0]
    x = resample(np.ascontiguousarray(x, dtype=np.float32), sr, target_sr)
    return np.clip(x, -1.0, 1.0).astype(np.float32, copy=False)


def estimate_snr_db(x: np.ndarray, sr: int = SR) -> float:
    """Cheap SNR estimate (reference for the Kotlin port): 30 ms non-overlapping frames, mean power
    per frame; noise = mean of the quietest 10 % frames, signal = mean of the loudest 50 % frames;
    SNR = 10*log10(signal/noise).  Returns +inf for silence-free/empty edge cases."""
    n = int(sr * 0.03)
    if len(x) < 4 * n:
        return float("inf")
    f = x[: len(x) // n * n].reshape(-1, n).astype(np.float64)
    p = np.sort((f * f).mean(axis=1))
    k_noise = max(1, len(p) // 10)
    noise = max(float(p[:k_noise].mean()), 1e-12)
    sig = float(p[len(p) // 2:].mean())
    return 10.0 * math.log10(max(sig, 1e-12) / noise)


def denoise(x: np.ndarray, model_path: str | Path, num_threads: int, sr: int = SR) -> np.ndarray:
    import sherpa_onnx as so

    cfg = so.OfflineSpeechDenoiserConfig(
        model=so.OfflineSpeechDenoiserModelConfig(
            gtcrn=so.OfflineSpeechDenoiserGtcrnModelConfig(model=str(model_path)),
            num_threads=num_threads))
    d = so.OfflineSpeechDenoiser(cfg)
    out = d.run(x, sr)
    y = np.asarray(out.samples, dtype=np.float32)
    if out.sample_rate != sr:
        y = resample(y, out.sample_rate, sr)
    return y
