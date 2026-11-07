"""Генерирует JSON-кейсы паритета Kotlin <-> refpipe для `:core` (engine_cases.json) ПРЯМО из функций эталона:
rescue_segments, pad_segments / pack_chunks, strip_dashes_text, build_words, normalize_gain, smooth_languages.
Сигналы описываются формулой (огибающая по отрезкам + целочисленный LCG-шум), которую Kotlin повторяет бит в бит.

  src/eval/refpipe/.venv/bin/python src/android/scripts/gen_engine_cases.py   # перезаписывает core/src/test/resources/engine_cases.json
"""
import json
import random
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(REPO / "src/eval/refpipe"))

from refpipe.asr import build_words  # noqa: E402
from refpipe.audio import normalize_gain  # noqa: E402
from refpipe.lid import smooth_languages  # noqa: E402
from refpipe.postprocess import strip_dashes_text  # noqa: E402
from refpipe.types import TimeSpan  # noqa: E402
from refpipe.vad import pack_chunks, pad_segments, rescue_segments  # noqa: E402

SR = 16000


def signal(total: int, env: list) -> np.ndarray:
    """env: [[start, end, amp], ...] (отсчёты); вне огибающей -- amp 0.0005. Шум: LCG, одинаковый в Kotlin."""
    i = np.arange(total, dtype=np.int64)
    s = (i * 1103515245 + 12345) & 0x7FFFFFFF
    noise = ((s >> 8) & 0xFFFF).astype(np.float64) / 32768.0 - 1.0
    amp = np.full(total, 0.0005, dtype=np.float64)
    for a, b, v in env:
        amp[a:b] = v
    return (amp * noise).astype(np.float32)


def rand_env(rng: random.Random, seconds: float) -> tuple[int, list]:
    total = int(seconds * SR)
    env, pos = [], 0
    while pos < total:
        d = int(rng.uniform(0.2, 3.0) * SR)
        v = rng.choice([0.0005, 0.002, 0.01, 0.05, 0.2])
        env.append([pos, min(total, pos + d), v])
        pos += d
    return total, env


def main():
    rng = random.Random(20261107)
    cases = {"rescue": [], "pack": [], "strip_dashes": [], "build_words": [], "normalize_gain": [], "smooth_languages": []}

    for k in range(14):
        total, env = rand_env(rng, rng.choice([5.0, 12.0, 25.0, 40.0]))
        x = signal(total, env)
        covered = []
        for a, b, v in env:
            if v >= 0.05 and rng.random() < 0.5:
                covered.append([a, b])
        rc = {"snr_db": rng.choice([8, 10, 12]), "abs_floor_db": rng.choice([-50, -55, -60]), "bridge_ms": rng.choice([200, 300, 400]),
              "min_ms": rng.choice([400, 600, 800]), "margin_ms": rng.choice([100, 200, 300])}
        out = rescue_segments(x, [tuple(c) for c in covered], rc)
        cases["rescue"].append({"total": total, "env": env, "covered": covered, "cfg": rc, "expected": [list(o) for o in out]})

    for k in range(16):
        total, env = rand_env(rng, rng.choice([20.0, 45.0, 90.0]))
        x = signal(total, env)
        # «сегменты Silero»: громкие участки огибающей, слегка сжатые
        raw = [[a + 800, b - 800] for a, b, v in env if v >= 0.01 and b - a > 4000]
        max_s = rng.choice([6.0, 10.0, 25.0])
        min_s = rng.choice([0.0, 1.0, 2.0])
        pad_ms = rng.choice([50, 150, 300])
        use_rescue = rng.random() < 0.5
        rc = {"enabled": True, "snr_db": 10, "abs_floor_db": -50, "bridge_ms": 300, "min_ms": 600, "margin_ms": 200}
        segs = [tuple(r) for r in raw]
        if use_rescue:
            segs = sorted(segs + rescue_segments(x, segs, rc))
        padded = pad_segments(segs, pad_ms, total)
        chunks = pack_chunks(padded, max_s, min_s, x)
        cases["pack"].append({"total": total, "env": env, "raw": raw, "pad_ms": pad_ms, "max_s": max_s, "min_s": min_s,
                              "rescue": rc if use_rescue else None,
                              "expected_padded": [list(p) for p in padded], "expected_chunks": [list(c) for c in chunks]})

    texts = ["Привет.— Как дела?", "Что?—Да!", "Тест…— пауза", "Обычный текст — тире в середине", "Слово-через-дефис.", "—", ".—", "Конец.–",
             "А…—…— Б", "Нет тире", "a.—b", "Да!— —", "Вопрос?— Ответ.—", "—Начало", "двойное.——тире"]
    for mode in ("off", "after_terminal", "all"):
        for t in texts:
            cases["strip_dashes"].append({"mode": mode, "text": t, "expected": strip_dashes_text(t, mode)})

    chars = list("привет мир, как дела? Хорошо.")
    bw = [
        (["▁При", "вет", ",", "▁мир", "."], [0.0, 0.2, 0.5, 0.8, 1.2]),
        (["▁Добрый", "▁день", "!"], [0.0, 0.5, 0.9]),
        (["«", "▁Да", "»", "▁нет", "?"], [0.0, 0.1, 0.4, 0.8, 1.0]),
        (["▁", "—", "▁да", "."], [0.0, 0.1, 0.5, 0.7]),
        (["▁да", "▁", ",", "▁нет"], [0.0, 0.1, 0.1, 0.5]),
        (["▁а", "▁", ".", "▁", ".", "▁б"], [0.0, 0.1, 0.2, 0.3, 0.4, 0.9]),
        (["<unk>", "▁да"], [0.0, 0.3]),
        (chars, [i * 0.04 for i in range(len(chars))]),
    ]
    for toks, ts in bw:
        for keep in (False, True):
            for tail, off in ((40, 0), (40, -30), (200, 0)):
                dur = [0.2] * len(toks) if (tail == 200 and keep) else None
                ch = TimeSpan(1000, 4000)
                w = build_words(toks, ts, None, ch, tail, off, keep, dur)
                cases["build_words"].append({"tokens": toks, "ts": ts, "chunk": [1000, 4000], "tail": tail, "offset": off, "keep": keep,
                                             "durations": dur, "expected": [[x.text, x.start_ms, x.end_ms] for x in w]})

    for amp in (0.00003, 0.0004, 0.006, 0.05, 0.3, 0.9, 1.0):
        for cfg in ({"enabled": True, "mode": "peak"}, {"enabled": True, "mode": "peak", "apply_if_peak_below_dbfs": -20},
                    {"enabled": True, "mode": "peak", "peak_dbfs": -3, "max_gain_db": 20},
                    {"enabled": True, "mode": "peak", "skip_if_peak_below_dbfs": -60}, {"enabled": False, "mode": "peak"}):
            x = np.full(1000, amp, dtype=np.float32)
            _, g = normalize_gain(x, cfg)
            cases["normalize_gain"].append({"peak": float(x.max()), "cfg": cfg, "expected_gain_db": g})

    for _ in range(30):
        n = rng.randint(0, 9)
        raw = [rng.choice([None, "ru", "en", "ru", "en", "uk"]) for _ in range(n)]
        durs = [rng.choice([800, 2000, 3000, 7000, 9000]) for _ in range(n)]
        cases["smooth_languages"].append({"raw": raw, "durs": durs, "expected": smooth_languages(raw, durs)})

    out = REPO / "src/android/core/src/test/resources/engine_cases.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    json.dump(cases, open(out, "w", encoding="utf-8"), ensure_ascii=False)
    print({k: len(v) for k, v in cases.items()}, "->", out)


if __name__ == "__main__":
    main()
