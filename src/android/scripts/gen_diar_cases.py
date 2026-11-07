"""JSON-кейсы паритета для `SpeakerRelabeler` (Kotlin) ПРЯМО из эталона `src/eval/bench_diar/bench_diar/spkphrase.py`:
юниты (make_units), эмбеддинги юнитов (embed_units с детерминированной функцией признаков), ward (merges, cut, silhouette),
метки (label_units) на синтетических эмбеддингах. Выход: core/src/test/resources/diar_cases.json.

  python3 src/android/scripts/gen_diar_cases.py      # нужен numpy (подойдёт venv refpipe: src/eval/refpipe/.venv/bin/python)
"""
import importlib.util
import json
import random
from dataclasses import asdict
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("spkphrase", REPO / "src/eval/bench_diar/bench_diar/spkphrase.py")
sp = importlib.util.module_from_spec(spec)
import sys
sys.modules["spkphrase"] = sp
spec.loader.exec_module(sp)
SR = 16000


def lcg_signal(total: int) -> np.ndarray:
    i = np.arange(total, dtype=np.int64)
    s = (i * 1103515245 + 12345) & 0x7FFFFFFF
    return ((((s >> 8) & 0xFFFF).astype(np.float64) / 32768.0 - 1.0) * 0.3).astype(np.float32)


def feature_embed(seg: np.ndarray):
    """Детерминированный «эмбеддинг»: точно воспроизводится в Kotlin (только точные операции над float32 -> float64)."""
    n = len(seg)
    return [float(n), float(seg[0]), float(seg[n // 2]), float(seg[-1]), float(seg[n // 3]), 1.0]


def rand_params(rng):
    return sp.Params(gap_ms=rng.choice([200, 300, 500]), max_unit_ms=rng.choice([4000, 12000]), split_min_side_ms=rng.choice([200, 300, 600]),
                     pad_ms=rng.choice([0, 40, 100]), embed_max_s=rng.choice([1.0, 2.5, 10.0]), min_reliable_ms=rng.choice([500, 800, 1500]),
                     k_min=rng.choice([2, 2, 3]), k_max=rng.choice([4, 6]), max_cluster_units=rng.choice([8, 400]), refine=rng.choice([0, 1, 2]))


def pjson(p):
    return asdict(p)


def main():
    rng = random.Random(46)
    nrng = np.random.default_rng(46)
    cases = {"units": [], "embed": [], "ward": [], "label": []}

    for _ in range(25):
        words, t = [], rng.randint(0, 500)
        for _ in range(rng.randint(1, 40)):
            s = t + rng.choice([0, 50, 150, 250, 350, 700, 2000])
            e = s + rng.randint(100, 900)
            words.append((s, e))
            t = e
        turns = None
        if rng.random() < 0.8:
            turns, a = [], 0
            while a < t + 500:
                b = a + rng.randint(300, 4000)
                turns.append((rng.randint(0, 3), a, b))
                a = b - rng.choice([0, 0, 100])
        p = rand_params(rng)
        u = sp.make_units(words, turns, p)
        cases["units"].append({"words": words, "turns": turns, "params": pjson(p), "expected": [list(x) for x in u]})

    for _ in range(12):
        total = rng.randint(2, 30) * SR
        audio = lcg_signal(total)
        words, t = [], rng.randint(0, 300)
        for _ in range(rng.randint(2, 14)):
            s = t + rng.choice([0, 100, 250, 400, 1500]); e = s + rng.randint(80, 2500)
            if e * SR // 1000 > total + SR:
                break
            words.append((s, e)); t = e
        if len(words) < 2:
            continue
        p = rand_params(rng)
        units = sp.make_units(words, None, p)
        E = sp.embed_units(audio, units, feature_embed, p)
        cases["embed"].append({"total": total, "words": words, "params": pjson(p), "units": [list(u) for u in units], "expected": E.tolist()})

    for _ in range(18):
        n = rng.randint(3, 45)
        dim = rng.choice([4, 8, 16])
        k = rng.randint(2, 4)
        c = nrng.normal(size=(k, dim))
        Y = np.stack([c[i % k] + rng.choice([0.05, 0.2, 0.5]) * nrng.normal(size=dim) for i in range(n)])
        Y /= np.linalg.norm(Y, axis=1, keepdims=True) + 1e-9
        mg = sp.ward_merges(Y)
        labs = {str(kk): sp.cut(n, mg, kk).tolist() for kk in range(2, min(6, n - 1) + 1)}
        sils = {kk: sp.silhouette(Y, np.array(l)) for kk, l in labs.items()}
        cases["ward"].append({"Y": Y.tolist(), "merges": [list(m) for m in mg], "cuts": labs, "silhouettes": sils})

    for _ in range(30):
        n = rng.randint(1, 60)
        dim = rng.choice([8, 16])
        k = rng.randint(1, 5)
        c = nrng.normal(size=(k, dim))
        noise = rng.choice([0.05, 0.3, 0.8])
        E = np.stack([c[rng.randrange(k)] + noise * nrng.normal(size=dim) for _ in range(n)])
        E /= np.linalg.norm(E, axis=1, keepdims=True) + 1e-9
        dur = np.array([rng.choice([300, 500, 900, 2000, 5000, 9000]) for _ in range(n)])
        p = rand_params(rng)
        lab = sp.label_units(E, dur, p)
        cases["label"].append({"E": E.tolist(), "dur": dur.tolist(), "params": pjson(p), "expected": lab.tolist()})

    out = REPO / "src/android/core/src/test/resources/diar_cases.json"
    json.dump(cases, open(out, "w", encoding="utf-8"))
    print({k: len(v) for k, v in cases.items()}, "->", out)


if __name__ == "__main__":
    main()
