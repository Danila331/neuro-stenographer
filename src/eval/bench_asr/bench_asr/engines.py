"""Обёртки над sherpa-onnx для всех кандидатов ASR. Единый интерфейс:

    eng = build(spec, num_threads=4, decoding="greedy_search", ...)
    hyp = eng.decode(samples_float32_16k)   -> Hyp(text, tokens, timestamps, ...)
"""
from __future__ import annotations
import os, time, glob
from dataclasses import dataclass, field
from pathlib import Path
import numpy as np
import sherpa_onnx

ROOT = Path(os.environ.get("ROOT", Path(__file__).resolve().parents[6]))
MODELS = ROOT / ".cache" / "models"


@dataclass
class Spec:
    """Описание кандидата. kind: nemo_ctc | nemo_transducer | transducer | whisper | t_one | zipformer_ctc."""
    key: str                # короткий ключ для таблиц
    dirname: str            # папка в .cache/models
    kind: str
    precision: str = "int8"  # int8 | fp32
    note: str = ""
    punct: bool = False     # модель выдаёт пунктуацию/регистр сама
    extra: dict = field(default_factory=dict)

    @property
    def path(self) -> Path:
        return MODELS / self.dirname


@dataclass
class Hyp:
    text: str
    tokens: list[str] = field(default_factory=list)
    timestamps: list[float] = field(default_factory=list)
    logprobs: list[float] = field(default_factory=list)
    wall_s: float = 0.0


def _pick(d: Path, pattern: str, precision: str) -> str:
    """Выбрать файл по шаблону с учётом precision (int8 -> *.int8.onnx, fp32 -> без int8)."""
    cands = sorted(glob.glob(str(d / pattern)))
    if not cands:
        raise FileNotFoundError(f"{d}/{pattern}")
    i8 = [c for c in cands if ".int8." in c]
    f32 = [c for c in cands if ".int8." not in c]
    if precision == "int8":
        return (i8 or f32)[0]
    return (f32 or i8)[0]


def _pick_mixed(d: Path, stem: str, precision: str) -> str:
    return _pick(d, f"{stem}*.onnx", precision)


class Engine:
    def __init__(self, spec: Spec, num_threads: int, decoding: str = "greedy_search",
                 max_active_paths: int = 4, hotwords_file: str = "", hotwords_score: float = 1.5,
                 blank_penalty: float = 0.0, language: str = "ru", token_ts: bool = False):
        self.spec, self.num_threads, self.decoding = spec, num_threads, decoding
        self.hotwords_file = hotwords_file
        d = spec.path
        p = spec.precision
        k = spec.kind
        t0 = time.perf_counter()
        self.online = False
        if k == "nemo_ctc":
            self.rec = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
                model=_pick_mixed(d, "model", p), tokens=str(d / "tokens.txt"), num_threads=num_threads)
        elif k in ("nemo_transducer", "transducer"):
            kw = {}
            if k == "nemo_transducer":
                kw["model_type"] = "nemo_transducer"
            if hotwords_file:
                # GigaAM: словарь = буквы -> modeling_unit=cjkchar, хотворды записаны буквами через пробел;
                # zipformer-ru: BPE -> modeling_unit=bpe + bpe.model, хотворды обычным текстом.
                if k == "nemo_transducer":
                    kw.update(hotwords_file=hotwords_file, hotwords_score=hotwords_score, modeling_unit="cjkchar")
                else:
                    kw.update(hotwords_file=hotwords_file, hotwords_score=hotwords_score, modeling_unit="bpe",
                              bpe_vocab=str(d / "bpe.model"))
            self.rec = sherpa_onnx.OfflineRecognizer.from_transducer(
                encoder=_pick_mixed(d, "encoder", p), decoder=_pick_mixed(d, "decoder", p),
                joiner=_pick_mixed(d, "joiner", p), tokens=str(d / "tokens.txt"),
                num_threads=num_threads, decoding_method=decoding, max_active_paths=max_active_paths,
                blank_penalty=blank_penalty, **kw)
        elif k == "whisper":
            enc = _pick(d, "*encoder*.onnx", p)
            dec = _pick(d, "*decoder*.onnx", p)
            tok = glob.glob(str(d / "*tokens.txt"))[0]
            self.rec = sherpa_onnx.OfflineRecognizer.from_whisper(
                encoder=enc, decoder=dec, tokens=tok, language=language, task="transcribe",
                num_threads=num_threads, decoding_method="greedy_search", enable_token_timestamps=token_ts)
        elif k == "t_one":
            self.online = True
            self.rec = sherpa_onnx.OnlineRecognizer.from_t_one_ctc(
                model=_pick(d, "model*.onnx", p), tokens=str(d / "tokens.txt"), num_threads=num_threads)
        elif k == "moonshine":      # v1: preprocess + encode + uncached/cached decode
            self.rec = sherpa_onnx.OfflineRecognizer.from_moonshine(
                preprocessor=_pick(d, "preprocess*.onnx", p), encoder=_pick(d, "encode*.onnx", p),
                uncached_decoder=_pick(d, "uncached_decode*.onnx", p), cached_decoder=_pick(d, "cached_decode*.onnx", p),
                tokens=str(d / "tokens.txt"), num_threads=num_threads)
        elif k == "moonshine_v2":   # v2 (2026): encoder + decoder
            self.rec = sherpa_onnx.OfflineRecognizer.from_moonshine_v2(
                encoder=_pick(d, "encoder*", p), decoder=_pick(d, "decoder*", p),
                tokens=str(d / "tokens.txt"), num_threads=num_threads)
        elif k == "online_transducer":
            self.online = True
            self.rec = sherpa_onnx.OnlineRecognizer.from_transducer(
                tokens=str(d / "tokens.txt"), encoder=_pick_mixed(d, "encoder", p), decoder=_pick_mixed(d, "decoder", p),
                joiner=_pick_mixed(d, "joiner", p), num_threads=num_threads, decoding_method=decoding,
                max_active_paths=max_active_paths)
        else:
            raise ValueError(k)
        self.load_s = time.perf_counter() - t0

    def decode(self, x: np.ndarray, sr: int = 16000, hotwords: str | None = None) -> Hyp:
        t0 = time.perf_counter()
        if self.online:
            s = self.rec.create_stream()
            s.accept_waveform(sr, x)
            s.accept_waveform(sr, np.zeros(int(0.66 * sr), dtype=np.float32))  # tail padding
            s.input_finished()
            while self.rec.is_ready(s):
                self.rec.decode_stream(s)
            r = self.rec.get_result_all(s) if hasattr(self.rec, "get_result_all") else self.rec.get_result(s)
            wall = time.perf_counter() - t0
            if isinstance(r, str):
                return Hyp(r.strip(), wall_s=wall)
            return Hyp(r.text.strip(), list(getattr(r, "tokens", [])), list(getattr(r, "timestamps", [])), wall_s=wall)
        if hotwords is not None and self.hotwords_file:
            s = self.rec.create_stream(hotwords)
        else:
            s = self.rec.create_stream()
        s.accept_waveform(sr, x)
        self.rec.decode_stream(s)
        r = s.result
        wall = time.perf_counter() - t0
        return Hyp(r.text.strip(), list(r.tokens), list(r.timestamps), list(getattr(r, "ys_log_probs", []) or []), wall)

    def decode_stream_raw(self, x, sr=16000):
        s = self.rec.create_stream(); s.accept_waveform(sr, x); self.rec.decode_stream(s); return s.result


def size_on_disk(spec: Spec, files: list[str] | None = None) -> int:
    """Байт на диске для выбранной точности (только веса, нужные для запуска)."""
    d = spec.path
    p = spec.precision
    tot = 0
    if spec.kind == "nemo_ctc":
        tot += os.path.getsize(_pick_mixed(d, "model", p))
    elif spec.kind in ("nemo_transducer", "transducer", "online_transducer"):
        for s in ("encoder", "decoder", "joiner"):
            tot += os.path.getsize(_pick_mixed(d, s, p))
    elif spec.kind == "whisper":
        tot += os.path.getsize(_pick(d, "*encoder*.onnx", p)) + os.path.getsize(_pick(d, "*decoder*.onnx", p))
        # внешние weights (large-v3)
        for w in glob.glob(str(d / "*weights*")):
            tot += os.path.getsize(w)
    elif spec.kind == "t_one":
        tot += os.path.getsize(_pick(d, "model*.onnx", p))
    elif spec.kind in ("moonshine", "moonshine_v2"):
        tot += sum(os.path.getsize(f) for f in glob.glob(str(d / "*")) if os.path.isfile(f)
                   and f.rsplit(".", 1)[-1] in ("onnx", "ort", "bin") and (".int8" in f or "quant" in f or True))
    for t in ("tokens.txt", "bpe.model"):
        if (d / t).exists():
            tot += os.path.getsize(d / t)
    return tot
