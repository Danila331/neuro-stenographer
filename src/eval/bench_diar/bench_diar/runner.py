"""Run sherpa-onnx OfflineSpeakerDiarization with disk cache + process pool."""
from __future__ import annotations

import hashlib
import json
import multiprocessing as mp
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

import numpy as np

from .data import CACHE, Item, load_audio
from .metrics import Score, Segments, aggregate, score

MODELS = CACHE / "models"
RESULTS = Path(__file__).resolve().parents[1] / "results"
RUN_CACHE = RESULTS / "cache"

SEG = {
    "pyannote3": MODELS / "sherpa-onnx-pyannote-segmentation-3-0",
    "reverb1": MODELS / "sherpa-onnx-reverb-diarization-v1",
}


def emb_path(name: str) -> Path:
    return MODELS / f"diar-emb-{name}" / "model.onnx"


@dataclass(frozen=True)
class Cfg:
    seg: str = "pyannote3"          # key in SEG
    seg_int8: bool = True
    emb: str = "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k"
    threshold: float = 0.5
    num_speakers: int = -1          # -1: auto
    min_on: float = 0.3
    min_off: float = 0.5
    threads: int = 2
    shift: float = 0.1              # window_shift_ratio of the segmentation model (sherpa default 0.1)

    def key(self) -> str:
        d = asdict(self)
        d.pop("threads")            # does not change the result
        return hashlib.md5(json.dumps(d, sort_keys=True).encode()).hexdigest()[:12]


def build(cfg: Cfg):
    import sherpa_onnx as so
    seg_model = SEG[cfg.seg] / ("model.int8.onnx" if cfg.seg_int8 else "model.onnx")
    c = so.OfflineSpeakerDiarizationConfig(
        segmentation=so.OfflineSpeakerSegmentationModelConfig(
            pyannote=so.OfflineSpeakerSegmentationPyannoteModelConfig(str(seg_model), cfg.shift),
            num_threads=cfg.threads),
        embedding=so.SpeakerEmbeddingExtractorConfig(str(emb_path(cfg.emb)), num_threads=cfg.threads),
        clustering=so.FastClusteringConfig(num_clusters=cfg.num_speakers, threshold=cfg.threshold),
        min_duration_on=cfg.min_on, min_duration_off=cfg.min_off,
    )
    assert c.validate(), "invalid diarization config"
    return so.OfflineSpeakerDiarization(c)


def run_one(sd, x: np.ndarray) -> tuple[Segments, float]:
    t = time.perf_counter()
    r = sd.process(x).sort_by_start_time()
    el = time.perf_counter() - t
    return [(f"spk{s.speaker}", float(s.start), float(s.end)) for s in r], el


_W: dict = {}


def _work(args):
    cfg, item = args
    f = RUN_CACHE / f"{cfg.key()}_{item.id}.json"
    if f.exists():
        return json.loads(f.read_text())
    if _W.get("cfg") != cfg:
        _W["cfg"], _W["sd"] = cfg, build(cfg)
    x = load_audio(item.audio)
    segs, el = run_one(_W["sd"], x)
    out = {"id": item.id, "segs": segs, "sec": el, "dur": len(x) / 16000}
    RUN_CACHE.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps(out))
    return out


def run_items(cfg: Cfg, items: list[Item], procs: int = 4) -> list[dict]:
    if procs <= 1:
        return [_work((cfg, it)) for it in items]
    ctx = mp.get_context("spawn")
    with ctx.Pool(procs) as p:
        return p.map(_work, [(cfg, it) for it in items], chunksize=1)


def evaluate(cfg: Cfg, items: list[Item], procs: int = 4, collars=(0.0, 0.25)):
    """Returns {collar: aggregate-dict}, per-item runs."""
    runs = run_items(cfg, items, procs)
    res = {}
    for c in collars:
        sc = [score(it.ref, r["segs"], it.duration, c) for it, r in zip(items, runs)]
        res[c] = aggregate(sc)
    tot_s, tot_d = sum(r["sec"] for r in runs), sum(r["dur"] for r in runs)
    res["rtf"] = tot_s / tot_d
    return res, runs
