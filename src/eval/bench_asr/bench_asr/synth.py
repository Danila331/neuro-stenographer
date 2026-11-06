"""Синтетические нерегулярные сигналы для проверки галлюцинаций: тишина, шумы, «мяуканье», мурлыканье, щелчки.
Мяуканье синтезировано (гармонический источник с переменным F0 + формантные фильтры) — это приближение,
реальные записи кошек в проект не входят (нет официального источника в списке разрешённых)."""
from __future__ import annotations
import numpy as np
from scipy import signal

SR = 16000


def _norm_db(x, dbfs):
    p = np.sqrt(np.mean(x ** 2)) + 1e-12
    return (x * (10 ** (dbfs / 20) / p)).astype(np.float32)


def silence_zero(sec, rng): return np.zeros(int(sec * SR), np.float32)


def noise_floor(sec, rng): return _norm_db(rng.randn(int(sec * SR)), -65)


def white(sec, rng, db=-35): return _norm_db(rng.randn(int(sec * SR)), db)


def pink(sec, rng, db=-30):
    n = int(sec * SR)
    X = np.fft.rfft(rng.randn(n))
    f = np.arange(len(X)); f[0] = 1
    return _norm_db(np.fft.irfft(X / np.sqrt(f), n), db)


def hum(sec, rng, db=-35):
    t = np.arange(int(sec * SR)) / SR
    x = sum(np.sin(2 * np.pi * 50 * k * t + rng.rand() * 6.28) / k for k in range(1, 6))
    return _norm_db(x + 0.05 * rng.randn(len(t)), db)


def clicks(sec, rng, db=-28):
    n = int(sec * SR); x = np.zeros(n)
    for p in rng.randint(0, n, size=int(sec * 4)):
        x[p] = rng.randn() * 5
    b, a = signal.butter(2, [800, 6000], "bandpass", fs=SR)
    return _norm_db(signal.lfilter(b, a, x) + 0.01 * rng.randn(n), db)


def _formant(x, freqs=(700, 1800, 3200), bws=(150, 250, 350)):
    y = np.zeros_like(x)
    for f, bw in zip(freqs, bws):
        b, a = signal.butter(2, [f - bw, f + bw], "bandpass", fs=SR)
        y += signal.lfilter(b, a, x)
    return y


def meow(rng, dur=None):
    dur = dur or rng.uniform(0.5, 1.3)
    n = int(dur * SR); t = np.arange(n) / SR
    f0 = rng.uniform(380, 520)
    # контур F0: подъём -> пик -> спад (классическое «мя-а-у»)
    contour = f0 * (1 + 0.9 * np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 1.2 * rng.uniform(0.7, 1.2) - 0.15 * t / dur)
    phase = 2 * np.pi * np.cumsum(contour) / SR
    src = sum(np.sin(k * phase) / (k ** 1.1) for k in range(1, 25))
    # скользящая форманта: «м» (носовая) -> «я/а» (открытая) -> «у» (закрытая)
    y = _formant(src, freqs=(rng.uniform(600, 900), rng.uniform(1700, 2400), 3300))
    env = np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 0.7
    y = y * env + 0.02 * rng.randn(n) * env
    return y / (np.abs(y).max() + 1e-9)


def meows(sec, rng, db=-22, density=0.5):
    n = int(sec * SR); x = np.zeros(n); pos = int(rng.uniform(0.2, 1.5) * SR)
    while pos < n - SR:
        m = meow(rng); x[pos:pos + len(m)] += m[: n - pos]
        pos += len(m) + int(rng.uniform(0.6, 3.5) / max(density, 0.1) * SR)
    return _norm_db(x + 0.002 * rng.randn(n), db)


def purr(sec, rng, db=-30):
    n = int(sec * SR); t = np.arange(n) / SR
    am = (np.sin(2 * np.pi * 26 * t) > 0.2).astype(float) * (0.6 + 0.4 * np.sin(2 * np.pi * 0.3 * t))
    b, a = signal.butter(2, [60, 1200], "bandpass", fs=SR)
    return _norm_db(signal.lfilter(b, a, rng.randn(n)) * am, db)


GENERATORS = {
    "silence_zero": silence_zero, "noise_floor": noise_floor, "white_-35dB": white, "pink_-30dB": pink,
    "hum50": hum, "clicks": clicks, "meow": meows, "purr": purr,
}


def make_all(sec=30, seeds=(0, 1, 2)):
    out = {}
    for name, g in GENERATORS.items():
        for s in seeds:
            out[f"{name}#{s}"] = g(sec, np.random.RandomState(1000 + s))
    return out


ESC_CATS = ["cat", "dog", "crying_baby", "laughing", "coughing", "keyboard_typing", "door_wood_knock", "footsteps", "rain",
            "clock_tick", "vacuum_cleaner", "snoring", "sneezing", "mouse_click", "washing_machine"]


def esc50_clips(root, sec=30, seeds=(0, 1), cats=ESC_CATS):
    """Реальные не-речевые звуки ESC-50 (karolpiczak/ESC-50, CC BY-NC): клипы по 5 с категории склеиваются в sec секунд.
    root = .../ESC-50 (с audio/ и meta/esc50.csv). Клипы нормируются по пику до -3 дБFS."""
    import csv, soundfile as sf
    rows = list(csv.DictReader(open(f"{root}/meta/esc50.csv", encoding="utf-8")))
    out = {}
    for cat in cats:
        files = [r["filename"] for r in rows if r["category"] == cat]
        if not files:
            continue
        for s in seeds:
            rng = np.random.RandomState(500 + s)
            order = rng.permutation(len(files)); parts = []; n = 0
            while n < sec * SR:
                x, sr = sf.read(f"{root}/audio/{files[order[len(parts) % len(files)]]}", dtype="float32")
                x = x if x.ndim == 1 else x.mean(1)
                x = x / (np.abs(x).max() + 1e-9) * 0.7
                parts.append(x); n += len(x)
            out[f"esc_{cat}#{s}"] = np.concatenate(parts)[: int(sec * SR)].astype(np.float32)
    return out
