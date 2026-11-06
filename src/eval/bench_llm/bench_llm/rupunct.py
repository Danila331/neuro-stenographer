"""Baseline without LLM: RUPunct (rubert-tiny2/mini based token classifier, MIT) via torch fp32 or ONNX int8."""
from __future__ import annotations

import json
import time
from pathlib import Path

import numpy as np

from .llama import MODELS
from .text import Tok, parse, score, total, fallback, guard_ok

MARK_MAP = {"PERIOD": ".", "COMMA": ",", "QUESTION": "?", "TIRE": "—", "VOSKL": "!", "MNOGOTOCHIE": ".",
            "QUESTIONVOSKL": "?", "O": "", "DVOETOCHIE": "", "PERIODCOMMA": ",", "DEFIS": ""}


class RuPunct:
    def __init__(self, size: str = "small", backend: str = "onnx-int8", threads: int = 4):
        from transformers import AutoTokenizer
        src = MODELS / f"punct-rupunct-{size}"
        self.tk = AutoTokenizer.from_pretrained(str(src), strip_accents=False, add_prefix_space=True)
        cfg = json.loads((src / "config.json").read_text())
        self.id2label = {int(k): v for k, v in cfg["id2label"].items()}
        self.backend = backend
        if backend == "torch":
            import torch
            from transformers import AutoModelForTokenClassification
            torch.set_num_threads(threads)
            self.m = AutoModelForTokenClassification.from_pretrained(str(src)).eval()
        else:
            import onnxruntime as ort
            so = ort.SessionOptions()
            so.intra_op_num_threads = threads
            fn = "model.int8.onnx" if backend == "onnx-int8" else "model.onnx"
            self.sess = ort.InferenceSession(str(MODELS / f"punct-rupunct-{size}-onnx" / fn), so, providers=["CPUExecutionProvider"])

    def logits(self, words: list[str]):
        enc = self.tk(words, is_split_into_words=True, return_tensors="np", truncation=True, max_length=512)
        if self.backend == "torch":
            import torch
            with torch.no_grad():
                lg = self.m(**{k: torch.from_numpy(v) for k, v in enc.items()}).logits[0].numpy()
        else:
            names = {i.name for i in self.sess.get_inputs()}
            lg = self.sess.run(None, {k: v.astype(np.int64) for k, v in enc.items() if k in names})[0][0]
        return enc.word_ids(0), lg

    def punctuate(self, words: list[str]) -> list[Tok]:
        wid, lg = self.logits(words)
        toks = [Tok(w, False, "") for w in words]
        seen = set()
        for pos, w in enumerate(wid):
            if w is None or w in seen:
                continue
            seen.add(w)
            lab = self.id2label[int(lg[pos].argmax())]
            case, _, rest = lab.partition("_")
            if case == "UPPER" and rest.startswith("TOTAL_"):
                rest = rest[6:]
            toks[w].cap = case == "UPPER"
            toks[w].mark = MARK_MAP.get(rest, "")
        return toks


def export_onnx(size: str):
    import torch
    from onnxruntime.quantization import quantize_dynamic, QuantType
    from transformers import AutoModelForTokenClassification, AutoTokenizer
    src = MODELS / f"punct-rupunct-{size}"
    dst = MODELS / f"punct-rupunct-{size}-onnx"
    tmp = MODELS / f".tmp-llm-bench-rupunct-{size}-onnx"
    if dst.exists():
        return dst
    tmp.mkdir(parents=True, exist_ok=True)
    m = AutoModelForTokenClassification.from_pretrained(str(src)).eval()
    tk = AutoTokenizer.from_pretrained(str(src), strip_accents=False, add_prefix_space=True)
    enc = tk(["привет", "как", "дела"], is_split_into_words=True, return_tensors="pt")
    names = [k for k in ("input_ids", "attention_mask", "token_type_ids") if k in enc]
    torch.onnx.export(m, tuple(enc[k] for k in names), str(tmp / "model.onnx"), input_names=names, output_names=["logits"],
                      dynamic_axes={**{k: {0: "b", 1: "s"} for k in names}, "logits": {0: "b", 1: "s"}}, opset_version=17,
                      dynamo=False)
    quantize_dynamic(str(tmp / "model.onnx"), str(tmp / "model.int8.onnx"), weight_type=QuantType.QInt8)
    for f in ("config.json", "vocab.txt", "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json"):
        (tmp / f).write_bytes((src / f).read_bytes())
    tmp.rename(dst)
    return dst


def evaluate(rp: RuPunct, utts: list) -> dict:
    counts, ms = [], 0.0
    nw = 0
    for u in utts:
        t0 = time.perf_counter()
        toks = rp.punctuate(u.words)
        ms += (time.perf_counter() - t0) * 1000
        nw += len(u.words)
        from .punct import fix_sentence_case
        toks = fix_sentence_case(toks)
        gold = fix_sentence_case(parse(u.text))
        counts.append(score(gold, toks))
    tot = total(counts)
    return {"n_utts": len(utts), "n_words": nw, "guard_pass": 1.0,
            "f1": {k: round(v.f1, 4) for k, v in tot.items()}, "prec": {k: round(v.p, 4) for k, v in tot.items()},
            "rec": {k: round(v.r, 4) for k, v in tot.items()}, "support": {k: v.tp + v.fn for k, v in tot.items()},
            "ms_per_word": ms / nw}
