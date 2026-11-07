"""llama-server wrapper: reuses the llm-bench build (tag b12177) and its `Server` class."""
from __future__ import annotations

import socket
import sys

from .data import ROOT

LLMB = ROOT / ".worktrees" / "llm-bench" / "src" / "eval" / "bench_llm"
BIN = LLMB / ".build" / "llama.cpp" / "build" / "bin"
GGUF = {
    "0.6b": ROOT / ".cache" / "models" / "llm" / "qwen3-0.6b-q4_0.gguf",
    "1.7b": ROOT / ".cache" / "models" / "llm" / "qwen3-1.7b-q4_k_m.gguf",
    "4b": ROOT / ".cache" / "models" / "llm" / "qwen3-4b-q4_k_m.gguf",
}


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


import os
import signal
import subprocess
import threading
import time
from contextlib import contextmanager

import psutil
import requests

FLAGS_PHONE = ["-nr", "-ctk", "q8_0", "-ctv", "q8_0", "-fa", "on"]   # recommended on-device flags (bench_llm README)


def chatml(system: str, user: str) -> str:
    """Qwen3, thinking disabled (empty think block) - exactly what PromptBuilder.chatml does."""
    return f"<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"


class Server:
    def __init__(self, model: str, threads: int = 4, n_ctx: int = 4096, extra: list[str] | None = None):
        self.port = free_port()
        cmd = [str(BIN / "llama-server"), "-m", str(GGUF[model]), "-c", str(n_ctx), "-t", str(threads), "-tb", str(threads),
               "-ngl", "0", "--host", "127.0.0.1", "--port", str(self.port), "--parallel", "1", "--no-webui", "--log-disable",
               "-ub", "512"] + (extra if extra is not None else FLAGS_PHONE)
        self.cmd = cmd
        self.proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                                     env={**os.environ, "DYLD_LIBRARY_PATH": str(BIN)}, start_new_session=True)
        self.url = f"http://127.0.0.1:{self.port}"
        t0 = time.time()
        while True:
            if self.proc.poll() is not None:
                raise RuntimeError(self.proc.stderr.read().decode()[-2000:])
            try:
                if requests.get(self.url + "/health", timeout=2).status_code == 200:
                    break
            except requests.RequestException:
                pass
            if time.time() - t0 > 300:
                raise TimeoutError
            time.sleep(0.3)
        self.load_s = time.time() - t0
        self.sess = requests.Session()
        self.peak_rss = 0
        self.peak_uss = 0
        self._stop = False
        self._ps = psutil.Process(self.proc.pid)
        self._th = threading.Thread(target=self._poll, daemon=True)
        self._th.start()

    def _poll(self):
        while not self._stop:
            try:
                self.peak_rss = max(self.peak_rss, self._ps.memory_info().rss)
                self.peak_uss = max(self.peak_uss, self._ps.memory_full_info().uss)
            except psutil.Error:
                return
            time.sleep(0.25)

    def close(self):
        self._stop = True
        if self.proc.poll() is None:
            os.killpg(self.proc.pid, signal.SIGTERM)
            try:
                self.proc.wait(10)
            except subprocess.TimeoutExpired:
                os.killpg(self.proc.pid, signal.SIGKILL)

    def complete(self, prompt: str, n_predict: int = 512, grammar: str | None = None, cache_prompt: bool = False) -> dict:
        body = {"prompt": prompt, "n_predict": n_predict, "temperature": 0.0, "top_k": 1, "cache_prompt": cache_prompt,
                "stop": ["<|im_end|>"], "samplers": ["top_k", "temperature"]}
        if grammar:
            body["grammar"] = grammar
        t0 = time.perf_counter()
        r = self.sess.post(self.url + "/completion", json=body, timeout=3600)
        wall = time.perf_counter() - t0
        r.raise_for_status()
        j = r.json()
        tm = j.get("timings", {})
        return {"text": j["content"].strip(), "prompt_n": tm.get("prompt_n", 0), "prompt_ms": tm.get("prompt_ms", 0.0), "cache_n": tm.get("cache_n", 0),
                "predicted_n": tm.get("predicted_n", 0), "predicted_ms": tm.get("predicted_ms", 0.0), "wall_ms": wall * 1000,
                "stop": j.get("stop_type")}

    def tokenize(self, text: str) -> list[int]:
        return self.sess.post(self.url + "/tokenize", json={"content": text, "add_special": False}).json()["tokens"]


@contextmanager
def server(model: str, **kw):
    s = Server(model, **kw)
    try:
        yield s
    finally:
        s.close()
