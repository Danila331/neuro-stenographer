"""Check the ONNX (fp32 and int8) embedder against sentence-transformers on a few Russian sentences."""
import sys

import numpy as np

from .embed import EMB, SPECS, OnnxEmbedder

S = ["Мастер называл сумму в двадцать тысяч рублей.", "Говорящий 2: Я за горы, там прохладно.", "Кто доложит про склад?", "Давайте голосовать."]


def main(name: str):
    from sentence_transformers import SentenceTransformer
    s = SPECS[name]
    st = SentenceTransformer(str(EMB / s.dir), device="cpu", trust_remote_code=False)
    ref = st.encode([s.dpre + x for x in S], normalize_embeddings=True)
    for onnx in (s.onnx.replace(".int8", ""), s.onnx):
        e = OnnxEmbedder(name, onnx=onnx)
        v = e.embed(S, "doc")
        print(name, onnx, "min cos vs ST:", round(float((v * ref).sum(1).min()), 4))


if __name__ == "__main__":
    for n in sys.argv[1:]:
        main(n)
