"""Один прогон: модель x потоки x датасет. Отдельный процесс => честная пиковая RAM.

python -m bench_asr.run_one --model gigaam3_rnnt --threads 4 --dataset <root> --out results/raw/x.jsonl
Пишет <out> (по строке на файл) и <out>.summary.json.
"""
from __future__ import annotations
import argparse, json, os, resource, sys, time, statistics
from pathlib import Path
import numpy as np
import psutil

from .engines import Engine, size_on_disk, ROOT
from .registry import REGISTRY
from .audio_norm import normalize, attenuate_int16
from .data import load_dataset, utterance_items
from .textnorm import wer_counts


def peak_rss_mb() -> float:
    r = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return r / (1024 * 1024) if sys.platform == "darwin" else r / 1024


def lp_stats(h):
    lp = h.logprobs
    if not lp:
        return None, None
    return float(np.mean(lp)), float(np.min(lp))


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--dataset", required=True)
    ap.add_argument("--split", default=None)
    ap.add_argument("--condition", default=None)
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--decoding", default="greedy_search")
    ap.add_argument("--max-active-paths", type=int, default=4)
    ap.add_argument("--hotwords-file", default="")
    ap.add_argument("--hotwords-score", type=float, default=1.5)
    ap.add_argument("--blank-penalty", type=float, default=0.0)
    ap.add_argument("--repeat", type=int, default=1, help="сколько раз повторить прогон для RTF (берётся медиана)")
    ap.add_argument("--token-ts", action="store_true")
    ap.add_argument("--utterances", action="store_true", help="диалоги -> отдельные реплики по эталонным меткам")
    ap.add_argument("--attenuate-db", type=float, default=0.0, help="имитация тихой записи (ослабление + int16)")
    ap.add_argument("--stride", type=int, default=1, help="брать каждый K-й файл (равномерная подвыборка)")
    ap.add_argument("--max-gain-db", type=float, default=30.0)
    ap.add_argument("--normalize", default="none", help="audio.normalize_gain: none|peak|rms (см. audio_norm.py)")
    ap.add_argument("--language", default="ru", help="язык для whisper (ru|en|auto-пусто)")
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)

    spec = REGISTRY[a.model]
    items = load_dataset(a.dataset, a.split, a.condition, None if a.utterances else a.limit)
    items = items[:: a.stride] if a.stride > 1 else items
    if a.utterances:
        items = utterance_items(items)
        items = items[: a.limit] if a.limit else items
    eng = Engine(spec, a.threads, a.decoding, a.max_active_paths, a.hotwords_file, a.hotwords_score,
                 a.blank_penalty, language=a.language, token_ts=a.token_ts)
    rss_after_load = psutil.Process().memory_info().rss / 2**20
    eng.decode(np.random.RandomState(0).randn(16000 * 2).astype(np.float32) * 0.01)  # прогрев
    audios = [normalize(attenuate_int16(it.load_audio(), a.attenuate_db), a.normalize, max_gain_db=a.max_gain_db)[0] for it in items]
    total_audio = sum(len(x) for x in audios) / 16000

    walls_total = []; cpu_total = []
    rows = []
    for rep in range(a.repeat):
        tot = 0.0; c0 = time.process_time()
        for it, x in zip(items, audios):
            h = eng.decode(x, hotwords=None)
            tot += h.wall_s
            if rep == 0:
                mean_lp, min_lp = lp_stats(h)
                rows.append({"id": it.id, "dur_s": len(x) / 16000, "wall_s": h.wall_s, "hyp": h.text,
                             "ref": it.ref_text, "condition": it.condition, "split": it.split,
                             "source": it.meta.get("source"), "scenario": it.meta.get("scenario"), "meta_id": it.id, "mean_lp": mean_lp, "min_lp": min_lp,
                             "tokens": h.tokens, "timestamps": h.timestamps})
        walls_total.append(tot); cpu_total.append(time.process_time() - c0)

    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    S = {m: [0, 0, 0, 0] for m in ("raw", "norm", "norm_num")}
    for r in rows:
        for m in S:
            c = wer_counts(r["ref"], r["hyp"], m)
            S[m] = [x + y for x, y in zip(S[m], c)]
    summ = {
        "model": a.model, "kind": spec.kind, "precision": spec.precision, "threads": a.threads,
        "decoding": a.decoding, "hotwords": bool(a.hotwords_file), "n_items": len(rows),
        "audio_s": total_audio,
        "wer_raw": (S["raw"][0] + S["raw"][1] + S["raw"][2]) / max(S["raw"][3], 1),
        "wer_norm": (S["norm"][0] + S["norm"][1] + S["norm"][2]) / max(S["norm"][3], 1),
        "wer_norm_num": (S["norm_num"][0] + S["norm_num"][1] + S["norm_num"][2]) / max(S["norm_num"][3], 1),
        "rtf": statistics.median(walls_total) / total_audio, "rtf_runs": [w / total_audio for w in walls_total],
        "cpu_rtf": statistics.median(cpu_total) / total_audio, "load_s": eng.load_s, "peak_rss_mb": peak_rss_mb(), "rss_after_load_mb": rss_after_load,
        "size_mb": size_on_disk(spec) / 2**20, "load_avg": os.getloadavg()[0], "ncpu": os.cpu_count(),
    }
    json.dump(summ, open(a.out + ".summary.json", "w"), indent=1)
    print(json.dumps({k: (round(v, 4) if isinstance(v, float) else v) for k, v in summ.items() if k != "rtf_runs"},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
