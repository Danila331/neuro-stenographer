"""Temporary synthetic Russian dialogs from single-speaker audiobook clips (RuLS, CC-BY-4.0).

Turns are cropped at low-energy points from 10-20 s clips of one reader, placed with pauses or
overlaps, then degraded per condition (clean / noise / reverb / phone / overlap).  Reference =
energy-VAD speech regions of every source clip at its placement (precise RTTM), plus utterance-level
ref/<id>.json in the project output format.
"""
from __future__ import annotations

import csv
import json
import random
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy import signal

from .data import write_rttm

SR = 16000


def _db(x, eps=1e-10):
    return 10 * np.log10(np.maximum(x, eps))


def frame_energy_db(x: np.ndarray, win=0.025, hop=0.010) -> np.ndarray:
    w, h = int(win * SR), int(hop * SR)
    if len(x) < w:
        return np.array([_db(np.mean(x ** 2) if len(x) else 0.0)])
    n = 1 + (len(x) - w) // h
    idx = np.arange(w)[None, :] + h * np.arange(n)[:, None]
    return _db(np.mean(x[idx] ** 2, axis=1))


def speech_regions(x: np.ndarray, drop_below_db=-32.0, bridge=0.30, min_len=0.12) -> list[tuple[float, float]]:
    """Energy VAD: frames within `drop_below_db` of the clip's 95th-percentile level."""
    e = frame_energy_db(x)
    thr = np.percentile(e, 95) + drop_below_db
    act = e > thr
    segs, i, n = [], 0, len(act)
    while i < n:
        if act[i]:
            j = i
            while j < n and act[j]:
                j += 1
            segs.append([i * 0.01, j * 0.01 + 0.015])
            i = j
        else:
            i += 1
    merged: list[list[float]] = []
    for a, b in segs:
        if merged and a - merged[-1][1] < bridge:
            merged[-1][1] = b
        else:
            merged.append([a, b])
    return [(a, min(b, len(x) / SR)) for a, b in merged if b - a >= min_len]


def quiet_points(x: np.ndarray) -> np.ndarray:
    e = frame_energy_db(x)
    thr = np.percentile(e, 95) - 30
    q = e < thr
    pts, i, n = [], 0, len(q)
    while i < n:
        if q[i]:
            j = i
            while j < n and q[j]:
                j += 1
            if j - i >= 6:
                pts.append(int((i + j) / 2 * 0.01 * SR))
            i = j
        else:
            i += 1
    return np.array([0] + pts + [len(x)])


def crop_turn(clip: np.ndarray, dur: float, rng: random.Random) -> np.ndarray:
    pts = quiet_points(clip)
    for _ in range(20):
        a = pts[rng.randrange(len(pts) - 1)]
        target = a + int(dur * SR)
        b = pts[np.argmin(np.abs(pts - target))]
        if b - a >= 0.4 * SR:
            return clip[a:b]
    a = rng.randrange(max(1, len(clip) - int(dur * SR)))
    return clip[a: a + int(dur * SR)]


def rms_norm(x, db=-25.0):
    r = np.sqrt(np.mean(x ** 2) + 1e-12)
    return x * (10 ** (db / 20) / r)


def synth_rir(rt60: float, rng: np.random.Generator, direct_db=0.0) -> np.ndarray:
    n = int(SR * min(rt60 * 1.2, 1.5))
    t = np.arange(n) / SR
    h = rng.standard_normal(n) * np.exp(-6.9078 * t / rt60)
    h[0] = 0
    h /= np.sqrt(np.sum(h ** 2)) + 1e-9
    d = np.zeros(n)
    d[int(0.002 * SR)] = 10 ** (direct_db / 20) * 1.6  # direct path stronger than reverberant part
    return d + h * 0.8


def add_noise(x, noise, snr_db):
    n = noise[: len(x)]
    if len(n) < len(x):
        n = np.tile(n, int(np.ceil(len(x) / len(n))))[: len(x)]
    ps = np.mean(x ** 2) + 1e-12
    pn = np.mean(n ** 2) + 1e-12
    return x + n * np.sqrt(ps / (pn * 10 ** (snr_db / 10)))


def pink(n, rng):
    w = rng.standard_normal(n)
    f = np.fft.rfft(w)
    f /= np.sqrt(np.maximum(np.arange(len(f)), 1))
    return np.fft.irfft(f, n)


def phone_channel(x, rng: np.random.Generator):
    sos = signal.butter(4, [300, 3400], btype="band", fs=SR, output="sos")
    y = signal.sosfilt(sos, x)
    y8 = signal.resample_poly(y, 1, 2)
    pk = np.max(np.abs(y8)) + 1e-9
    y8 = y8 / pk
    mu = 255.0  # mu-law 8-bit quantisation
    c = np.sign(y8) * np.log1p(mu * np.abs(y8)) / np.log1p(mu)
    c = np.round(c * 127) / 127
    y8 = np.sign(c) * ((1 + mu) ** np.abs(c) - 1) / mu
    y = signal.resample_poly(y8 * pk, 2, 1)
    return add_noise(y, rng.standard_normal(len(y)), rng.uniform(22, 32))


COND = {
    # name: (overlap_prob, max_overlap_s)
    "clean": (0.10, 1.2), "noise": (0.10, 1.2), "reverb": (0.10, 1.2),
    "phone": (0.10, 1.2), "overlap": (0.45, 2.0),
}


def make_dialog(pool: dict[str, list[np.ndarray]], readers: list[str], condition: str, seed: int,
                total_s: tuple[float, float] = (45, 90)):
    rng = random.Random(seed)
    nrng = np.random.default_rng(seed)
    n_spk = rng.choices([2, 3, 4], weights=[.45, .35, .20])[0]
    spk_readers = rng.sample(readers, n_spk)
    gains = {r: rng.uniform(-4, 4) for r in spk_readers}
    rirs = {}
    if condition == "reverb":
        rt = rng.uniform(0.35, 0.9)
        rirs = {r: synth_rir(rt * rng.uniform(0.9, 1.1), nrng) for r in spk_readers}
    ov_p, ov_max = COND[condition]
    target = rng.uniform(*total_s)
    placed = []  # (reader, start_s, audio, regions)
    t, prev = 0.3, None
    cur = rng.choice(spk_readers)
    while t < target:
        if prev is not None:
            cur = rng.choice([r for r in spk_readers if r != prev]) if rng.random() < 0.88 else prev
        u = rng.random()
        dur = rng.uniform(0.7, 2.2) if u < .3 else rng.uniform(2.2, 5.5) if u < .8 else rng.uniform(5.5, 10)
        clip = rng.choice(pool[cur])
        a = crop_turn(clip, dur, rng)
        a = rms_norm(a) * 10 ** (gains[cur] / 20) * 10 ** (rng.uniform(-2, 2) / 20)
        regions = speech_regions(a)
        if not regions:
            continue
        # trim leading/trailing non-speech of the crop to keep the placement precise
        s0 = max(0.0, regions[0][0] - 0.05)
        s1 = min(len(a) / SR, regions[-1][1] + 0.05)
        a = a[int(s0 * SR): int(s1 * SR)]
        regions = [(x - s0, y - s0) for x, y in regions]
        if prev is not None and rng.random() < ov_p and placed:
            pa = placed[-1]
            pdur = len(pa[2]) / SR
            ov = min(rng.uniform(0.3, ov_max), 0.5 * pdur, 0.5 * len(a) / SR)
            start = pa[1] + pdur - ov
        else:
            start = t + (rng.uniform(0.15, 1.2) if prev is not None else 0.0)
        placed.append((cur, start, a, regions))
        t = max(t, start + len(a) / SR)
        prev = cur
    dur_s = t + 0.5
    mix = np.zeros(int(dur_s * SR) + SR)
    ref = []
    for r, st, a, regions in placed:
        if r in rirs:
            rms0 = np.sqrt(np.mean(a ** 2)) + 1e-9
            a = signal.fftconvolve(a, rirs[r])
            a = a * (rms0 / (np.sqrt(np.mean(a ** 2)) + 1e-9))
        i0 = int(st * SR)
        mix[i0: i0 + len(a)] += a[: len(mix) - i0]
        for x, y in regions:
            ref.append((r, st + x, st + y))
    mix = mix[: int(dur_s * SR)]
    if condition == "noise":
        snr = rng.uniform(3, 15)
        if rng.random() < 0.5:  # babble: other readers
            others = [r for r in pool if r not in spk_readers]
            b = np.concatenate([rng.choice(pool[rng.choice(others)]) for _ in range(int(dur_s / 10) + 2)])
            b = b[: len(mix)] if len(b) >= len(mix) else np.tile(b, 2)[: len(mix)]
            mix = add_noise(mix, b, snr + 4)
        mix = add_noise(mix, pink(len(mix), nrng) if rng.random() < .6 else nrng.standard_normal(len(mix)), snr)
    elif condition == "phone":
        mix = phone_channel(mix, nrng)
    pk = np.max(np.abs(mix)) + 1e-9
    mix = (mix / pk * 0.8).astype(np.float32)
    # final per-reader relabel to generic ids
    names = {r: f"S{i + 1}" for i, r in enumerate(spk_readers)}
    ref = sorted([(names[r], round(a, 3), round(min(b, len(mix) / SR), 3)) for r, a, b in ref], key=lambda z: z[1])
    return mix, ref, n_spk


def build(pool_dir: Path, out: Path, plan: dict[str, dict[str, int]], seed: int = 1234,
          readers_split: dict[str, list[str]] | None = None):
    """plan = {split: {condition: n_dialogs}}; readers_split = {split: [reader ids]} (disjoint)."""
    (out / "audio").mkdir(parents=True, exist_ok=True)
    (out / "ref").mkdir(exist_ok=True)
    (out / "rttm").mkdir(exist_ok=True)
    pool: dict[str, list[np.ndarray]] = {}
    for d in sorted(p for p in pool_dir.iterdir() if p.is_dir()):
        pool[d.name] = [sf.read(str(f), dtype="float32")[0] for f in sorted(d.glob("*.wav"))]
    rows = []
    k = 0
    for split, conds in plan.items():
        rs = readers_split[split]
        sub = {r: pool[r] for r in rs}
        for cond, n in conds.items():
            for i in range(n):
                k += 1
                uid = f"synth_{split}_{cond}_{i:02d}"
                mix, ref, n_spk = make_dialog(sub, rs, cond, seed + k * 7919)
                sf.write(out / "audio" / f"{uid}.wav", mix, SR, subtype="PCM_16")
                write_rttm(out / "rttm" / f"{uid}.rttm", uid, ref)
                utt = merge_utts(ref)
                (out / "ref" / f"{uid}.json").write_text(json.dumps({"transcript": utt}, ensure_ascii=False))
                rows.append([uid, f"audio/{uid}.wav", f"ref/{uid}.json", int(len(mix) / SR * 1000), n_spk,
                             "ru", "synth-ruls", cond, split])
    with open(out / "manifest.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["id", "audio", "ref", "duration_ms", "num_speakers", "lang", "source", "condition", "split"])
        w.writerows(rows)


def merge_utts(ref, gap=1.0):
    out = []
    byspk: dict[str, list] = {}
    for s, a, b in ref:
        byspk.setdefault(s, []).append([a, b])
    for s, segs in byspk.items():
        segs.sort()
        cur = segs[0][:]
        for a, b in segs[1:]:
            if a - cur[1] <= gap:
                cur[1] = max(cur[1], b)
            else:
                out.append((s, *cur))
                cur = [a, b]
        out.append((s, *cur))
    out.sort(key=lambda z: (z[1], z[2]))
    return [{"speaker_id": "speaker_" + s[1:], "start_ms": int(a * 1000), "end_ms": int(b * 1000), "text": "реплика"}
            for s, a, b in out]
