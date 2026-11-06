"""Оркестратор: запускает run_one в отдельных процессах (изоляция пиковой RAM), резюмируемо.

python -m bench_asr.run_matrix --dataset <root> --tag fleurs --models all
Фазы:
  acc    T=4, весь набор (whisper — первые --whisper-limit файлов)
  speed  T=1 и T=4 на первых --speed-limit файлах, --repeat прогонов (медиана)
Результаты: results/raw/<tag>/<phase>__<model>__t<T>.jsonl(+.summary.json)
"""
from __future__ import annotations
import argparse, subprocess, sys, json
from pathlib import Path
from .registry import REGISTRY

HERE = Path(__file__).resolve().parents[1]
FAST = ["gigaam2_ctc", "gigaam2_rnnt", "gigaam3_ctc", "gigaam3_rnnt", "gigaam3_ctc_punct", "gigaam3_rnnt_punct",
        "zip_ru_2504_int8", "zip_ru_2504_fp32", "zip_small_2409", "zip_small_2409_fp32", "t_one"]
WHISPER = ["whisper_turbo_int8", "whisper_small_int8", "whisper_small_fp32",
           "whisper_medium_int8", "whisper_large_v3_int8"]


def ready(key) -> bool:
    return REGISTRY[key].path.exists()


def run(model, threads, dataset, limit, repeat, out, extra=()):
    if Path(str(out) + ".summary.json").exists():
        print("skip", out.name); return
    cmd = [sys.executable, "-m", "bench_asr.run_one", "--model", model, "--threads", str(threads),
           "--dataset", str(dataset), "--repeat", str(repeat), "--out", str(out)]
    if limit:
        cmd += ["--limit", str(limit)]
    cmd += list(extra)
    print(">>", " ".join(cmd[3:]), flush=True)
    r = subprocess.run(cmd, cwd=HERE, capture_output=True, text=True)
    print(r.stdout[-600:], r.stderr[-400:] if r.returncode else "", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--models", default="all")
    ap.add_argument("--phases", default="acc,speed")
    ap.add_argument("--whisper-limit", type=int, default=40)
    ap.add_argument("--speed-limit", type=int, default=30)
    ap.add_argument("--repeat", type=int, default=3)
    ap.add_argument("--acc-threads", type=int, default=4)
    ap.add_argument("--extra", default="", help="доп. аргументы run_one, напр. '--utterances --split dev'")
    ap.add_argument("--suffix", default="", help="суффикс имени файла (варианты условий)")
    a = ap.parse_args()
    models = FAST + WHISPER if a.models == "all" else a.models.split(",")
    outdir = HERE / "results" / "raw" / a.tag
    outdir.mkdir(parents=True, exist_ok=True)
    for ph in a.phases.split(","):
        for m in models:
            if not ready(m):
                print("not ready:", m); continue
            w = m.startswith("whisper")
            if ph == "acc":
                run(m, a.acc_threads, a.dataset, a.whisper_limit if w else None, 1, outdir / f"acc__{m}{a.suffix}__t4.jsonl", a.extra.split())
            else:
                big = w and ("medium" in m or "large" in m or "fp32" in m)
                lim = (8 if big else 12) if w else a.speed_limit
                rep = 1 if w else a.repeat
                for t in (1, 4):
                    run(m, t, a.dataset, lim, rep, outdir / f"speed__{m}__t{t}.jsonl", a.extra.split())


if __name__ == "__main__":
    main()
