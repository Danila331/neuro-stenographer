"""Tiny job scheduler: runs benchmark jobs as soon as the model file exists and < MAXP jobs are running.
python -m bench_llm.scheduler [--maxp 4]"""
from __future__ import annotations

import argparse
import subprocess
import sys
import time

from .llama import HERE, model_path

R = HERE / "results"
PY = sys.executable


def punct(model, variants, lang="ru"):
    sfx = "" if lang == "ru" else "_en"
    outs = [R / "punct" / f"{model}__{v}{sfx}.json" for v in variants]
    cmd = [PY, "-m", "bench_llm.run_punct", "--models", model, "--variants", *variants] + (["--lang", "en"] if lang == "en" else [])
    return model, outs, cmd, f"punct_{model}{sfx}"


def diar(model, mode):
    return model, [R / "diarfix" / f"{model}__{mode}.json"], [PY, "-m", "bench_llm.run_diarfix", "--models", model, "--modes", mode], f"diar_{model}_{mode}"


def proto(model):
    return model, [R / "protocol" / f"{model}.json"], [PY, "-m", "bench_llm.run_protocol", "--models", model], f"proto_{model}"


def mem(model):
    return model, [R / "mem" / f"{model}.json"], [PY, "-m", "bench_llm.run_mem", "--models", model], f"mem_{model}"


TASKS = [
    punct("qwen3-1.7b-q8_0", ["b"]), punct("qwen2.5-1.5b-q4_k_m", ["b"]), punct("qvikhr3-1.7b-q4_k_m", ["b"]),
    punct("vikhr-qwen2.5-1.5b-q4_k_m", ["b"]), punct("qwen2.5-0.5b-q4_k_m", ["b"]), punct("qwen2.5-3b-q4_k_m", ["b"]),
    diar("qwen3-1.7b-q8_0", "grammar"), diar("qwen3-4b-q4_k_m", "grammar"),
    punct("qwen3-1.7b-q8_0", ["b"], "en"), punct("qwen3-0.6b-q8_0", ["b"], "en"), punct("qwen2.5-1.5b-q4_k_m", ["b"], "en"),
    proto("qwen3-1.7b-q8_0"), proto("qwen3-4b-q4_k_m"),
    punct("qvikhr3-4b-q4_k_m", ["b"]), punct("vikhr-qwen2.5-0.5b-q4_k_m", ["b"]),
    punct("qwen3-4b-q4_k_m", ["b"], "en"),
    punct("qwen3-1.7b-q8_0", ["a"]), punct("qwen2.5-1.5b-q4_k_m", ["a"]), punct("qwen3-1.7b-q8_0", ["c"]),
    diar("qwen2.5-3b-q4_k_m", "grammar"), diar("qwen2.5-1.5b-q4_k_m", "grammar"), diar("qvikhr3-4b-q4_k_m", "grammar"),
    proto("qwen2.5-3b-q4_k_m"), proto("qvikhr3-4b-q4_k_m"), proto("qwen2.5-1.5b-q4_k_m"), proto("qvikhr3-1.7b-q4_k_m"),
    punct("qwen2.5-0.5b-q8_0", ["b"]), punct("qwen2.5-1.5b-q8_0", ["b"]),
    diar("qwen3-1.7b-q8_0", "free"), punct("qwen3-0.6b-q8_0", ["c"]),
]


def ps_lines() -> list[str]:
    return subprocess.run(["ps", "-eo", "command"], capture_output=True, text=True).stdout.splitlines()


def my_servers(lines: list[str]) -> int:
    return sum(1 for l in lines if "llama-server" in l and "/llm-bench/" in l)


def already_running(cmd: list[str], lines: list[str]) -> bool:
    key = " ".join(cmd[2:])  # "bench_llm.run_x --models M ..."
    return any(key in l for l in lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--maxp", type=int, default=4)
    a = ap.parse_args()
    started: set[str] = set()
    (HERE / "logs").mkdir(exist_ok=True)
    while True:
        lines = ps_lines()
        pending = [t for t in TASKS if not all(o.exists() for o in t[1])]
        busy = [t for t in pending if already_running(t[2], lines) or t[3] in started and any(
            "bench_llm.run_" in l and f"--models {t[0]}" in l for l in lines)]
        if not pending:
            print("all done", flush=True)
            return
        if my_servers(lines) < a.maxp:
            for model, outs, cmd, name in pending:
                if name in started and any(f"--models {model}" in l and cmd[2] in l for l in lines):
                    continue
                if not model_path(model).exists() or already_running(cmd, lines) or name in started:
                    continue
                started.add(name)
                subprocess.Popen(cmd, cwd=HERE, stdout=open(HERE / "logs" / f"{name}.log", "w"), stderr=subprocess.STDOUT)
                print(time.strftime("%H:%M:%S"), "started", name, flush=True)
                break
        time.sleep(20)


if __name__ == "__main__":
    main()
