"""Dense embedders: ONNX Runtime (int8/fp32 BERT-like) and llama.cpp server (GGUF)."""
from __future__ import annotations

import json
import os
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .data import MODELS

EMB = MODELS / "embed"


@dataclass
class Spec:
    name: str
    pool: str = "mean"          # mean | cls | last
    qpre: str = ""
    dpre: str = ""
    maxlen: int = 256
    dir: str = ""               # sub-directory in EMB
    onnx: str = "model.int8.onnx"
    tok: str = "tokenizer.json"
    license: str = ""
    repo: str = ""


SPECS: dict[str, Spec] = {s.name: s for s in [
    Spec("e5-small", "mean", "query: ", "passage: ", dir="e5-small", onnx="onnx/model.int8.onnx", tok="onnx/tokenizer.json", license="MIT", repo="intfloat/multilingual-e5-small"),
    Spec("e5-small-offq", "mean", "query: ", "passage: ", dir="e5-small", onnx="onnx/model_qint8_avx512_vnni.onnx", tok="onnx/tokenizer.json", license="MIT", repo="intfloat/multilingual-e5-small"),
    Spec("e5-base", "mean", "query: ", "passage: ", dir="e5-base", onnx="onnx/model.int8.onnx", tok="onnx/tokenizer.json", license="MIT", repo="intfloat/multilingual-e5-base"),
    Spec("user-base", "mean", "search_query: ", "search_document: ", dir="user-base", license="Apache-2.0", repo="deepvk/USER-base"),
    Spec("user2-small", "mean", "search_query: ", "search_document: ", dir="user2-small", license="Apache-2.0", repo="deepvk/USER2-small"),
    Spec("rubert-tiny2", "cls", dir="rubert-tiny2", license="MIT", repo="cointegrated/rubert-tiny2"),
    Spec("rubert-tiny-turbo", "cls", dir="rubert-tiny-turbo", license="MIT", repo="sergeyzh/rubert-tiny-turbo"),
    Spec("labse-ru-turbo", "cls", dir="labse-ru-turbo", license="MIT", repo="sergeyzh/LaBSE-ru-turbo"),
]}


class Embedder:
    name: str
    dim: int

    def embed(self, texts: list[str], kind: str) -> np.ndarray:  # kind: query | doc
        raise NotImplementedError

    def close(self) -> None:
        pass


def _norm(x: np.ndarray) -> np.ndarray:
    return x / np.maximum(np.linalg.norm(x, axis=1, keepdims=True), 1e-9)


class OnnxEmbedder(Embedder):
    def __init__(self, name: str, threads: int = 4, onnx: str | None = None):
        import onnxruntime as ort
        from tokenizers import Tokenizer

        self.spec = s = SPECS[name]
        self.name = name
        d = EMB / s.dir
        self.tok = Tokenizer.from_file(str(d / s.tok))
        self.tok.enable_truncation(max_length=s.maxlen)
        self.tok.enable_padding()
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        self.path = d / (onnx or s.onnx)
        self.sess = ort.InferenceSession(str(self.path), so, providers=["CPUExecutionProvider"])
        self.inputs = {i.name for i in self.sess.get_inputs()}
        self.dim = self.sess.get_outputs()[0].shape[-1]
        self.tokens_seen = 0

    def embed(self, texts: list[str], kind: str = "doc", batch: int = 16) -> np.ndarray:
        pre = self.spec.qpre if kind == "query" else self.spec.dpre
        texts = [pre + t for t in texts]
        order = np.argsort([len(t) for t in texts])
        out = np.zeros((len(texts), self.dim), dtype=np.float32)
        for i in range(0, len(texts), batch):
            idx = order[i:i + batch]
            enc = self.tok.encode_batch([texts[j] for j in idx])
            ids = np.array([e.ids for e in enc], dtype=np.int64)
            am = np.array([e.attention_mask for e in enc], dtype=np.int64)
            feed = {"input_ids": ids, "attention_mask": am}
            if "token_type_ids" in self.inputs:
                feed["token_type_ids"] = np.zeros_like(ids)
            self.tokens_seen += int(am.sum())
            h = self.sess.run(None, feed)[0]
            if self.spec.pool == "cls":
                v = h[:, 0]
            else:
                m = am[..., None].astype(np.float32)
                v = (h * m).sum(1) / np.maximum(m.sum(1), 1)
            out[idx] = v
        return _norm(out)


class LlamaEmbedder(Embedder):
    """Qwen3-Embedding GGUF through llama-server --embeddings (same binary as the LLM)."""

    def __init__(self, name: str = "qwen3-emb-0.6b", gguf: str | None = None, threads: int = 4, instruct: bool = True):
        import requests

        from .llm import BIN, free_port

        self.name = name
        self.requests = requests
        self.instruct = instruct
        self.port = free_port()
        path = gguf or str(EMB / "qwen3-emb-0.6b" / "Qwen3-Embedding-0.6B-Q8_0.gguf")
        self.path = Path(path)
        cmd = [str(BIN / "llama-server"), "-m", path, "--embeddings", "--pooling", "last", "-c", "2048", "-t", str(threads),
               "-tb", str(threads), "-ngl", "0", "--host", "127.0.0.1", "--port", str(self.port), "--no-webui", "-ub", "1024", "-b", "1024", "--parallel", "1", "--log-disable"]
        self.proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env={**os.environ, "DYLD_LIBRARY_PATH": str(BIN)}, start_new_session=True)
        t0 = time.time()
        while True:
            if self.proc.poll() is not None:
                raise RuntimeError('llama-server (embeddings) died')
            try:
                if requests.get(f"http://127.0.0.1:{self.port}/health", timeout=2).status_code == 200:
                    break
            except requests.RequestException:
                pass
            time.sleep(0.3)
        self.load_s = time.time() - t0
        self.dim = len(self._one("test"))

    def _one(self, t: str) -> list[float]:
        r = self.requests.post(f"http://127.0.0.1:{self.port}/embedding", json={"content": t}, timeout=600).json()
        v = r[0]["embedding"] if isinstance(r, list) else r["embedding"]
        return v[0] if v and isinstance(v[0], list) else v

    def embed(self, texts: list[str], kind: str = "doc") -> np.ndarray:
        if kind == "query" and self.instruct:
            texts = [f"Instruct: Given a question about a conversation transcript, retrieve the transcript fragment that answers it\nQuery:{t}" for t in texts]
        return _norm(np.array([self._one(t + "<|endoftext|>") for t in texts], dtype=np.float32))

    def close(self) -> None:
        import signal
        if self.proc.poll() is None:
            os.killpg(self.proc.pid, signal.SIGTERM)


class TorchEmbedder(Embedder):
    """Reference fp32 PyTorch embedder (sentence-transformers) for models whose ONNX export is not exact (DeBERTa)."""

    def __init__(self, name: str, threads: int = 4):
        import torch
        from sentence_transformers import SentenceTransformer
        torch.set_num_threads(threads)
        self.spec = SPECS[name]
        self.name = name
        self.path = EMB / self.spec.dir
        self.m = SentenceTransformer(str(self.path), device="cpu")
        self.dim = self.m.get_sentence_embedding_dimension()

    def embed(self, texts: list[str], kind: str = "doc") -> np.ndarray:
        pre = self.spec.qpre if kind == "query" else self.spec.dpre
        return self.m.encode([pre + t for t in texts], normalize_embeddings=True, batch_size=16).astype(np.float32)


def make(name: str, threads: int = 4, **kw) -> Embedder:
    if name.endswith("-torch"):
        return TorchEmbedder(name[:-6], threads=threads)
    if name.startswith("qwen3-emb"):
        return LlamaEmbedder(name, threads=threads, **kw)
    return OnnxEmbedder(name, threads=threads, **kw)
