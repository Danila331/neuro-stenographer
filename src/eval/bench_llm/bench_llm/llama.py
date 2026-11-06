"""Thin wrapper over llama.cpp `llama-server` (CPU-only build, tag pinned in README)."""
from __future__ import annotations

import json
import os
import signal
import socket
import subprocess
import time
from contextlib import contextmanager
from pathlib import Path

import requests

HERE = Path(__file__).resolve().parent.parent
BIN = HERE / ".build" / "llama.cpp" / "build" / "bin"
ROOT = Path(os.environ.get("ROOT", HERE.parents[3].parent))  # .../neuro-stenographer-claude
MODELS = Path(os.environ.get("MODELS_DIR", ROOT / ".cache" / "models"))
PROMPTS = HERE / "prompts"

# name -> (gguf path relative to MODELS, chat family)
CATALOG: dict[str, tuple[str, str]] = {
    "qwen3-0.6b-q8_0": ("llm-qwen3-0.6b-q8_0/Qwen3-0.6B-Q8_0.gguf", "qwen3"),
    "qwen3-0.6b-q4_k_m": ("llm-qwen3-0.6b-q4_k_m/Qwen3-0.6B-Q4_K_M.gguf", "qwen3"),
    "qwen3-1.7b-q8_0": ("llm-qwen3-1.7b-q8_0/Qwen3-1.7B-Q8_0.gguf", "qwen3"),
    "qwen3-1.7b-q4_k_m": ("llm-qwen3-1.7b-q4_k_m/Qwen3-1.7B-Q4_K_M.gguf", "qwen3"),
    "qwen3-4b-q4_k_m": ("llm-qwen3-4b-q4_k_m/Qwen3-4B-Q4_K_M.gguf", "qwen3"),
    "qwen2.5-0.5b-q4_k_m": ("llm-qwen2.5-0.5b-q4_k_m/qwen2.5-0.5b-instruct-q4_k_m.gguf", "chatml"),
    "qwen2.5-0.5b-q8_0": ("llm-qwen2.5-0.5b-q8_0/qwen2.5-0.5b-instruct-q8_0.gguf", "chatml"),
    "qwen2.5-1.5b-q4_k_m": ("llm-qwen2.5-1.5b-q4_k_m/qwen2.5-1.5b-instruct-q4_k_m.gguf", "chatml"),
    "qwen2.5-1.5b-q8_0": ("llm-qwen2.5-1.5b-q8_0/qwen2.5-1.5b-instruct-q8_0.gguf", "chatml"),
    "qwen2.5-3b-q4_k_m": ("llm-qwen2.5-3b-q4_k_m/qwen2.5-3b-instruct-q4_k_m.gguf", "chatml"),
    "vikhr-qwen2.5-0.5b-q4_k_m": ("llm-vikhr-qwen2.5-0.5b-q4_k_m/Vikhr-Qwen-2.5-0.5B-instruct-Q4_K_M.gguf", "chatml"),
    "vikhr-qwen2.5-1.5b-q4_k_m": ("llm-vikhr-qwen2.5-1.5b-q4_k_m/Vikhr-Qwen-2.5-1.5b-Instruct-Q4_K_M.gguf", "chatml"),
    "qvikhr3-1.7b-q4_k_m": ("llm-qvikhr3-1.7b-q4_k_m/QVikhr-3-1.7B-Instruction-noreasoning-Q4_K_M.gguf", "qwen3"),
    "qvikhr3-4b-q4_k_m": ("llm-qvikhr3-4b-q4_k_m/QVikhr-3-4B-Instruction-Q4_K_M.gguf", "qwen3"),
}


def model_path(name: str) -> Path:
    return MODELS / CATALOG[name][0]


def chatml(messages: list[dict], family: str, add_generation_prompt: bool = True) -> str:
    """Render ChatML. family 'qwen3' appends an empty think block (== enable_thinking=False)."""
    s = ""
    for m in messages:
        s += f"<|im_start|>{m['role']}\n{m['content']}<|im_end|>\n"
    if add_generation_prompt:
        s += "<|im_start|>assistant\n"
        if family == "qwen3":
            s += "<think>\n\n</think>\n\n"
    return s


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Server:
    def __init__(self, model: str, threads: int = 4, n_ctx: int = 4096, extra: list[str] | None = None,
                 batch_threads: int | None = None):
        self.model = model
        self.port = _free_port()
        cmd = [str(BIN / "llama-server"), "-m", str(model_path(model)), "-c", str(n_ctx),
               "-t", str(threads), "-tb", str(batch_threads or threads), "-ngl", "0",
               "--host", "127.0.0.1", "--port", str(self.port), "--parallel", "1",
               "--no-webui", "--log-disable", "-ub", "512"] + (extra or [])
        self.cmd = cmd
        self.proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                                     env={**os.environ, "DYLD_LIBRARY_PATH": str(BIN)}, start_new_session=True)
        self.url = f"http://127.0.0.1:{self.port}"
        t0 = time.time()
        while True:
            if self.proc.poll() is not None:
                raise RuntimeError(f"llama-server died: {self.proc.stderr.read().decode()[-2000:]}")
            try:
                if requests.get(self.url + "/health", timeout=2).status_code == 200:
                    break
            except requests.RequestException:
                pass
            if time.time() - t0 > 300:
                raise TimeoutError("llama-server start timeout")
            time.sleep(0.3)
        self.load_s = time.time() - t0
        self.sess = requests.Session()

    def close(self) -> None:
        if self.proc.poll() is None:
            os.killpg(self.proc.pid, signal.SIGTERM)
            try:
                self.proc.wait(10)
            except subprocess.TimeoutExpired:
                os.killpg(self.proc.pid, signal.SIGKILL)

    def tokenize(self, text: str) -> list[int]:
        return self.sess.post(self.url + "/tokenize", json={"content": text, "add_special": False}).json()["tokens"]

    def complete(self, prompt: str, n_predict: int = 256, grammar: str | None = None, stop: list[str] | None = None,
                 temperature: float = 0.0, extra: dict | None = None) -> dict:
        body = {"prompt": prompt, "n_predict": n_predict, "temperature": temperature, "top_k": 1 if temperature == 0 else 40,
                "cache_prompt": True, "stop": stop or ["<|im_end|>"], "samplers": ["top_k", "temperature"] if temperature == 0 else None}
        if body["samplers"] is None:
            del body["samplers"]
        if grammar:
            body["grammar"] = grammar
        if extra:
            body.update(extra)
        t0 = time.perf_counter()
        r = self.sess.post(self.url + "/completion", json=body, timeout=1800)
        wall = time.perf_counter() - t0
        r.raise_for_status()
        j = r.json()
        tm = j.get("timings", {})
        return {
            "text": j["content"].strip(),
            "prompt_n": tm.get("prompt_n", 0), "prompt_ms": tm.get("prompt_ms", 0.0),
            "predicted_n": tm.get("predicted_n", 0), "predicted_ms": tm.get("predicted_ms", 0.0),
            "cache_n": tm.get("cache_n", 0),
            "wall_ms": wall * 1000, "stop": j.get("stop_type"),
        }


@contextmanager
def server(model: str, **kw):
    s = Server(model, **kw)
    try:
        yield s
    finally:
        s.close()


def load_json(name: str):
    return json.loads((PROMPTS / name).read_text(encoding="utf-8"))


def load_text(name: str) -> str:
    return (PROMPTS / name).read_text(encoding="utf-8").strip("\n")
