"""Искажённые варианты FLEURS-фраз: шум (розовый SNR 10 дБ), «бормотание» (4 других фразы, SNR 10), телефон
(300-3400 Гц, 8 кГц), реверберация (синтетическая RIR, RT60~0.6 с). Первые N фраз набора.
python scripts/make_conditions.py --src <dataset_root> --out <out_parent> --n 60
-> <out_parent>/asr-bench-cond-{pink10,babble10,phone,reverb}/ (формат ARCHITECTURE §2)"""
import argparse, csv, json, shutil
from pathlib import Path
import numpy as np, soundfile as sf
from scipy import signal
from bench_asr.synth import pink

SR = 16000
ap = argparse.ArgumentParser()
ap.add_argument("--src", required=True); ap.add_argument("--out", required=True); ap.add_argument("--n", type=int, default=60)
a = ap.parse_args()
src, out = Path(a.src), Path(a.out)
rows = list(csv.DictReader(open(src / "manifest.csv", encoding="utf-8")))[: a.n]
pool = [sf.read(src / r["audio"], dtype="float32")[0] for r in list(csv.DictReader(open(src / "manifest.csv", encoding="utf-8")))[-40:]]
rng = np.random.RandomState(7)


def rms(x): return np.sqrt(np.mean(x ** 2)) + 1e-9


def mix(x, n, snr):
    n = np.resize(n, len(x))
    return x + n * (rms(x) / rms(n)) * 10 ** (-snr / 20)


def pink10(x): return mix(x, pink(len(x) / SR, rng, -30), 10)


def babble10(x):
    n = np.zeros(len(x))
    for _ in range(4):
        p = pool[rng.randint(len(pool))]; o = rng.randint(0, max(1, len(p) // 2)); n += np.resize(p[o:], len(x))
    return mix(x, n, 10)


def phone(x):
    b, aa = signal.butter(4, [300, 3400], "bandpass", fs=SR)
    y = signal.lfilter(b, aa, x)
    y = signal.resample_poly(signal.resample_poly(y, 1, 2), 2, 1)       # 16k -> 8k -> 16k
    return y[: len(x)]


def reverb(x):
    t = np.arange(int(0.6 * SR)) / SR
    rir = rng.randn(len(t)) * np.exp(-6.9 * t / 0.6); rir[0] = 1.0
    return signal.fftconvolve(x, rir / np.abs(rir).sum() * 4)[: len(x)]


for name, fn in [("pink10", pink10), ("babble10", babble10), ("phone", phone), ("reverb", reverb)]:
    d = out / f"asr-bench-cond-{name}"
    (d / "audio").mkdir(parents=True, exist_ok=True); (d / "ref").mkdir(exist_ok=True)
    man = []
    for r in rows:
        x, _ = sf.read(src / r["audio"], dtype="float32")
        y = fn(x); y = y * (rms(x) / rms(y)); y = np.clip(y, -1, 1)
        sf.write(d / "audio" / f"{r['id']}.wav", (y * 32767).astype("int16"), SR, subtype="PCM_16")
        shutil.copy(src / r["ref"], d / "ref" / f"{r['id']}.json")
        man.append([r["id"], f"audio/{r['id']}.wav", r["ref"], r["duration_ms"], 1, "ru", f"fleurs-{name}", {"phone": "phone", "reverb": "reverb"}.get(name, "noise"), "dev"])
    with open(d / "manifest.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f); w.writerow("id,audio,ref,duration_ms,num_speakers,lang,source,condition,split".split(",")); w.writerows(man)
    print(name, len(man))
