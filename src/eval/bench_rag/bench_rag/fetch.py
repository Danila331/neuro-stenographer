"""Download embedding models (official Hugging Face repos of the authors, no tokens) into the shared cache.

Atomic: download into .cache/models/.tmp-rag-<name>/, then rename to .cache/models/embed/<name>/.
"""
from __future__ import annotations

import shutil
import sys

from huggingface_hub import snapshot_download

from .data import MODELS

# name -> (repo, license, allow patterns)
REPOS = {
    "e5-small": ("intfloat/multilingual-e5-small", "MIT", ["onnx/model.onnx", "onnx/tokenizer.json", "tokenizer.json", "config.json", "*.txt", "special_tokens_map.json", "tokenizer_config.json", "sentencepiece.bpe.model"]),
    "e5-base": ("intfloat/multilingual-e5-base", "MIT", ["onnx/model.onnx", "onnx/tokenizer.json", "tokenizer.json", "config.json", "special_tokens_map.json", "tokenizer_config.json"]),
    "user-base": ("deepvk/USER-base", "Apache-2.0", ["*.json", "model.safetensors", "*.txt", "1_Pooling/*"]),
    "user2-small": ("deepvk/USER2-small", "Apache-2.0", ["*.json", "model.safetensors", "*.txt", "1_Pooling/*"]),
    "rubert-tiny2": ("cointegrated/rubert-tiny2", "MIT", ["*.json", "model.safetensors", "*.txt", "1_Pooling/*"]),
    "rubert-tiny-turbo": ("sergeyzh/rubert-tiny-turbo", "MIT", ["*.json", "model.safetensors", "*.txt", "1_Pooling/*"]),
    "labse-ru-turbo": ("sergeyzh/LaBSE-ru-turbo", "MIT", ["*.json", "model.safetensors", "*.txt", "1_Pooling/*", "*.model"]),
    "qwen3-emb-0.6b": ("Qwen/Qwen3-Embedding-0.6B-GGUF", "Apache-2.0", ["Qwen3-Embedding-0.6B-Q8_0.gguf", "*.md", "*.json"]),
}


def fetch(name: str) -> None:
    repo, _lic, allow = REPOS[name]
    dst = MODELS / "embed" / name
    if dst.exists():
        print("have", name)
        return
    tmp = MODELS / f".tmp-rag-{name}"
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True)
    snapshot_download(repo, local_dir=str(tmp), allow_patterns=allow)
    shutil.rmtree(tmp / ".cache", ignore_errors=True)
    (MODELS / "embed").mkdir(exist_ok=True)
    tmp.rename(dst)
    print("done", name, sum(f.stat().st_size for f in dst.rglob("*") if f.is_file()) // 2**20, "MiB")


if __name__ == "__main__":
    for n in sys.argv[1:] or REPOS:
        fetch(n)
