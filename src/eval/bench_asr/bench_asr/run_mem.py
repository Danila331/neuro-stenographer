"""Пиковая RSS модели при инференсе в чистом процессе (без датасета в памяти): загрузка -> чанк 10 с -> чанк 25 с.

python -m bench_asr.run_mem --model gigaam3_rnnt --threads 4 --audio <wav> --out results/mem.jsonl
Берётся первые 25 с аудио (по умолчанию — первая запись asr-bench-long). Результат: rss после загрузки, пик после 10 с, пик после 25 с.
"""
from __future__ import annotations
import argparse, json, resource, sys, time
import numpy as np, psutil, soundfile as sf
from .engines import Engine, ROOT
from .registry import REGISTRY


def peak_mb():
    r = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return r / 2**20 if sys.platform == "darwin" else r / 1024


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True); ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--audio", default=str(ROOT / ".cache/data/asr-bench-long/audio/long_00.wav"))
    ap.add_argument("--language", default="ru"); ap.add_argument("--out", required=True)
    a = ap.parse_args()
    x, sr = sf.read(a.audio, dtype="float32"); x = x if x.ndim == 1 else x.mean(1)
    base = psutil.Process().memory_info().rss / 2**20
    eng = Engine(REGISTRY[a.model], a.threads, language=a.language)
    after_load = psutil.Process().memory_info().rss / 2**20; pk_load = peak_mb()
    t = time.perf_counter(); eng.decode(x[: 10 * sr]); t10 = time.perf_counter() - t
    pk10 = peak_mb()
    ts = []
    for _ in range(3):
        t = time.perf_counter(); eng.decode(x[: 25 * sr]); ts.append(time.perf_counter() - t)
    t25 = sorted(ts)[1]; pk25 = peak_mb()
    r = {"model": a.model, "threads": a.threads, "base_rss_mb": base, "rss_after_load_mb": after_load, "peak_after_load_mb": pk_load,
         "peak_after_10s_mb": pk10, "peak_after_25s_mb": pk25, "model_delta_mb": pk25 - base, "rtf_25s_median": t25 / 25, "rtf_10s_first": t10 / 10, "load_avg": __import__("os").getloadavg()[0]}
    print(json.dumps({k: round(v, 1) if isinstance(v, float) else v for k, v in r.items()}))
    with open(a.out, "a", encoding="utf-8") as f:
        f.write(json.dumps(r) + "\n")


if __name__ == "__main__":
    main()
