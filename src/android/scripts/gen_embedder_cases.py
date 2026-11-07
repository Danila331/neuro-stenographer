"""Эталонные векторы эмбеддеров (Python onnxruntime, `bench_rag.embed.OnnxEmbedder`) для теста `OrtTextEmbedderTest` (Kotlin):
несколько строк как документы и как запросы, USER2-small и rubert-tiny-turbo. Выход: engine-embed/src/test/resources/embedder_cases.json.

  src/eval/bench_rag/.venv/bin/python src/android/scripts/gen_embedder_cases.py
"""
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "src/eval/bench_rag"))
from bench_rag.embed import make  # noqa: E402

OUT = REPO / "src/android/engine-embed/src/test/resources/embedder_cases.json"
TEXTS = [
    "Говорящий 1: Давайте обсудим бюджет на рыбу.\nГоворящий 2: В июле потратили восемь тысяч.",
    "Сколько мышей насчитали на складе?",
    "Говорящий 3: Назначим дежурной Багиру, она согласна.",
    "Hello, this is a short English sentence about fish budget.",
    "короткое",
    "Слово " * 200,
]


def main():
    cases = {}
    for name in ("user2-small", "rubert-tiny-turbo"):
        e = make(name, threads=2)
        cases[name] = {"texts": TEXTS,
                       "doc": [[float(x) for x in v] for v in e.embed(TEXTS, "doc")],
                       "query": [[float(x) for x in v] for v in e.embed(TEXTS, "query")], "dim": e.dim,
                       # по одному: int8-модели квантуют активации динамически -- результат зависит от состава батча (cos ~0.99)
                       "doc_single": [[float(x) for x in e.embed([s], "doc")[0]] for s in TEXTS],
                       "query_single": [[float(x) for x in e.embed([s], "query")[0]] for s in TEXTS]}
        e.close()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(cases, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(OUT, OUT.stat().st_size // 1024, "KiB")


if __name__ == "__main__":
    main()
