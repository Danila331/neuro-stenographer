"""Для parity.sh: пиковая нормализация WAV по audio.normalize_gain конфига (эталон bench_asr/audio_norm.py::normalize, режим peak).
refpipe пока не умеет normalize_gain, поэтому для него нормализованная копия готовится отдельно (float32 WAV, без потерь);
Android нормализует сам (AudioNormalizer) -- так проверяется независимость двух реализаций.
usage: parity_norm.py <config.json> <in.wav> <out.wav>   (если mode != peak -- копия без изменений)"""
import json
import sys

import numpy as np
import soundfile as sf

cfg = json.load(open(sys.argv[1], encoding="utf-8")).get("audio", {}).get("normalize_gain") or {}
x, sr = sf.read(sys.argv[2], dtype="float32")
if x.ndim > 1:
    x = x.mean(axis=1).astype(np.float32)


def db(v):
    return 20 * np.log10(max(v, 1e-9))


if str(cfg.get("mode", "none")).lower() == "peak" and len(x):
    pk = float(np.abs(x).max())
    if db(pk) >= -85:
        g = float(cfg.get("target_peak_dbfs", -1.0)) - db(pk)
        g = float(np.clip(g, -float(cfg.get("max_attenuation_db", 6.0)), float(cfg.get("max_gain_db", 40.0))))
        g = min(g, -1.0 - db(pk))
        x = (x * 10 ** (g / 20)).astype(np.float32)
sf.write(sys.argv[3], x, sr, subtype="FLOAT")
