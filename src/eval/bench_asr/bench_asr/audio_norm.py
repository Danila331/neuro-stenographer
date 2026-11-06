"""Нормализация громкости перед VAD/ASR (BACKLOG B24): кандидат на `audio.normalize_gain`.

Режимы:
  none — как есть
  peak — усилить так, чтобы пик = peak_db (дБFS), усиление не больше max_gain_db
  rms  — привести RMS по «активным» окнам (20 мс, выше gate_db от пика окон... см. ниже) к target_db,
         усиление в [-min_gain_db, +max_gain_db]; если после усиления пик > ceil_db, усиление уменьшается (без клиппинга)
Активные окна: энергия окна >= (макс. энергия окон - 40 дБ) и не ниже -75 дБFS абсолютно — чтобы тихий шумовой пол
не определял громкость. Если файл почти нулевой (пик < -85 дБFS) — не трогаем (усиливать нечего).
"""
from __future__ import annotations
import numpy as np

EPS = 1e-9


def db(x: float) -> float:
    return 20 * np.log10(max(x, EPS))


def active_rms_db(x: np.ndarray, win: int = 320, rel_db: float = 40.0, abs_floor_db: float = -75.0) -> float:
    n = len(x) // win
    if n == 0:
        return db(np.sqrt(np.mean(x ** 2)) if len(x) else 0)
    r = np.sqrt((x[: n * win].reshape(n, win) ** 2).mean(1))
    rd = 20 * np.log10(np.maximum(r, EPS))
    thr = max(rd.max() - rel_db, abs_floor_db)
    act = r[rd >= thr]
    return db(np.sqrt(np.mean(act ** 2))) if len(act) else db(np.sqrt(np.mean(r ** 2)))


def normalize(x: np.ndarray, mode: str = "none", target_db: float = -20.0, peak_db: float = -1.0,
              max_gain_db: float = 30.0, min_gain_db: float = 6.0, ceil_db: float = -1.0) -> tuple[np.ndarray, float]:
    """-> (x', gain_db)."""
    if mode == "none" or len(x) == 0:
        return x, 0.0
    pk = float(np.abs(x).max())
    if db(pk) < -85:
        return x, 0.0
    if mode == "peak":
        g = peak_db - db(pk)
    elif mode == "rms":
        g = target_db - active_rms_db(x)
    else:
        raise ValueError(mode)
    g = float(np.clip(g, -min_gain_db, max_gain_db))
    g = min(g, ceil_db - db(pk))            # без клиппинга
    return (x * 10 ** (g / 20)).astype(np.float32), g


def attenuate_int16(x: np.ndarray, att_db: float) -> np.ndarray:
    """Имитация тихой записи: ослабить и квантовать в int16 (теряются младшие разряды, как у реального тихого файла)."""
    if att_db == 0:
        return x
    return (np.round(x * 10 ** (-att_db / 20) * 32768) / 32768).astype(np.float32)
