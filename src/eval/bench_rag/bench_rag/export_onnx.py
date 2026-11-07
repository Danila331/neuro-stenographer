"""HF safetensors -> ONNX (last_hidden_state) -> int8 dynamic quantisation.  python -m bench_rag.export_onnx rubert-tiny2 ...

e5-small/base already ship an fp32 ONNX: only quantise (onnx/model.onnx -> onnx/model.int8.onnx).
"""
from __future__ import annotations

import sys

import torch
from onnxruntime.quantization import QuantType, quantize_dynamic
from transformers import AutoModel, AutoTokenizer

from .embed import EMB, SPECS


def export(name: str) -> None:
    s = SPECS[name]
    d = EMB / s.dir
    out = d / s.onnx
    if out.exists():
        print("have", out)
        return
    fp32 = d / s.onnx.replace(".int8", "")
    if not fp32.exists():
        tok = AutoTokenizer.from_pretrained(str(d))
        model = AutoModel.from_pretrained(str(d), attn_implementation="eager").eval()
        enc = tok(["пример текста для трассировки", "ещё один пример"], padding=True, return_tensors="pt")
        names = [k for k in ("input_ids", "attention_mask", "token_type_ids") if k in enc]
        args = tuple(enc[k] for k in names)

        class W(torch.nn.Module):
            def __init__(self, m):
                super().__init__()
                self.m = m

            def forward(self, *a):
                return self.m(**dict(zip(names, a))).last_hidden_state

        torch.onnx.export(W(model), args, str(fp32), input_names=names, output_names=["last_hidden_state"],
                          dynamic_axes={**{n: {0: "b", 1: "t"} for n in names}, "last_hidden_state": {0: "b", 1: "t"}},
                          opset_version=17, dynamo=False)
    quantize_dynamic(str(fp32), str(out), weight_type=QuantType.QInt8)
    print(name, "fp32", fp32.stat().st_size >> 20, "MiB; int8", out.stat().st_size >> 20, "MiB")


if __name__ == "__main__":
    for n in sys.argv[1:]:
        export(n)
