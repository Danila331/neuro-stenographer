"""Staged re-implementation of sherpa-onnx's pyannote diarization pipeline with disk caching.

Same algorithm as sherpa's reference python demo (speaker-diarization-onnx.py shipped with the model
archives) and the C++ OfflineSpeakerDiarizationPyannoteImpl, split in stages so that clustering sweeps
and algorithmic experiments do not recompute the expensive parts:

  A  segmentation (onnxruntime, pyannote-3.0 / reverb-v1)      -> chunk x frame x 3 binary labels
  B  per (chunk, local speaker) embedding (sherpa extractor)  -> embeddings   [dominant cost]
  C  clustering (sherpa FastClustering = same code as the APK) -> labels
  D  frame assembly + min_duration_on/off                      -> turns

Parity with the stock `OfflineSpeakerDiarization` is checked by `parity.py` (DER delta).
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .metrics import Segments
from .runner import RESULTS, SEG, emb_path

SR = 16000
STAGE_CACHE = RESULTS / "stage_cache"


class SegModel:
    _cache: dict = {}

    def __init__(self, path: Path, threads: int = 1):
        import onnxruntime as ort
        o = ort.SessionOptions()
        o.intra_op_num_threads = threads
        o.inter_op_num_threads = 1
        self.sess = ort.InferenceSession(str(path), sess_options=o, providers=["CPUExecutionProvider"])
        m = self.sess.get_modelmeta().custom_metadata_map
        self.window = int(m["window_size"])
        self.sr = int(m["sample_rate"])
        self.rf_size = int(m["receptive_field_size"])
        self.rf_shift = int(m["receptive_field_shift"])
        self.n_spk = int(m["num_speakers"])
        self.n_cls = int(m["num_classes"])
        self.max_cls = int(m["powerset_max_classes"])
        self.inp = self.sess.get_inputs()[0].name
        self.out = self.sess.get_outputs()[0].name
        mp = np.zeros((self.n_cls, self.n_spk), dtype=np.int8)
        k = 1
        for j in range(self.n_spk):
            mp[k, j] = 1
            k += 1
        for j in range(self.n_spk):
            for m2 in range(j + 1, self.n_spk):
                mp[k, j] = mp[k, m2] = 1
                k += 1
        self.mapping = mp

    @classmethod
    def get(cls, path: Path):
        if path not in cls._cache:
            cls._cache[path] = cls(path)
        return cls._cache[path]

    def chunks_labels(self, audio: np.ndarray, shift_ratio: float) -> tuple[np.ndarray, int]:
        """Returns (labels[C,F,S] int8, window_shift)."""
        w = self.window
        sh = int(shift_ratio * w)
        n = len(audio)
        if n <= w:
            starts = [0]
        else:
            starts = list(range(0, n - w + 1, sh))
            if (n - w) % sh > 0:
                starts.append(starts[-1] + sh)
        buf = np.zeros((len(starts), w), dtype=np.float32)
        for i, s in enumerate(starts):
            seg = audio[s: s + w]
            buf[i, : len(seg)] = seg
        outs = []
        for i in range(len(buf)):  # batch=1 on purpose: batched runs of the exported models differ (checked)
            y = self.sess.run([self.out], {self.inp: buf[i: i + 1, None, :]})[0]
            outs.append(np.argmax(y, axis=-1))
        cls = np.concatenate(outs)                      # C, F
        return self.mapping[cls].astype(np.int8), sh


def _h(*a) -> str:
    return hashlib.md5("|".join(map(str, a)).encode()).hexdigest()[:10]


def stage_a(item_id: str, audio: np.ndarray, seg: str, int8: bool, shift: float):
    f = STAGE_CACHE / f"A_{item_id}_{seg}_{int(int8)}_{shift}.npz"
    sm = SegModel.get(SEG[seg] / ("model.int8.onnx" if int8 else "model.onnx"))
    if f.exists():
        z = np.load(f)
        return z["labels"], int(z["sh"]), sm
    labels, sh = sm.chunks_labels(audio, shift)
    STAGE_CACHE.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(f, labels=labels, sh=sh)
    return labels, sh, sm


def stage_b(item_id: str, audio: np.ndarray, labels: np.ndarray, sh: int, sm: SegModel, tag: str, emb: str,
            exclude_overlap: bool, threads: int = 2, min_frames: int = 10):
    """Embeddings for every (chunk, local speaker) with >= min_frames active frames."""
    f = STAGE_CACHE / f"B_{item_id}_{tag}_{emb}_{int(exclude_overlap)}_{min_frames}.npz"
    if f.exists():
        z = np.load(f)
        return z["pairs"], z["embs"]
    import sherpa_onnx as so
    ex = so.SpeakerEmbeddingExtractor(so.SpeakerEmbeddingExtractorConfig(str(emb_path(emb)), num_threads=threads))
    L = labels.copy()
    if exclude_overlap:
        L = L * (L.sum(-1, keepdims=True) < 2)
    C, F, S = L.shape
    pad = np.concatenate([audio, np.zeros(sm.window + sh, dtype=np.float32)])
    pairs, embs = [], []
    for i in range(C):
        off = i * sh
        for j in range(S):
            fr = L[i, :, j]
            if fr.sum() < min_frames:
                continue
            d = np.diff(np.concatenate([[0], fr, [0]]))
            st, en = np.where(d == 1)[0], np.where(d == -1)[0]
            parts = [pad[int(a / F * sm.window) + off: int(b / F * sm.window) + off] for a, b in zip(st, en)]
            wav = np.concatenate(parts)
            s = ex.create_stream()
            s.accept_waveform(SR, wav)
            s.input_finished()
            embs.append(np.asarray(ex.compute(s), dtype=np.float32))
            pairs.append((i, j))
    pairs = np.array(pairs, dtype=np.int32).reshape(-1, 2)
    embs = np.stack(embs) if embs else np.zeros((0, 1), dtype=np.float32)
    np.savez_compressed(f, pairs=pairs, embs=embs)
    return pairs, embs


def cluster_fast(embs: np.ndarray, threshold: float, num_clusters: int) -> np.ndarray:
    import sherpa_onnx as so
    if len(embs) == 0:
        return np.zeros(0, dtype=int)
    if len(embs) == 1:
        return np.zeros(1, dtype=int)
    c = so.FastClustering(so.FastClusteringConfig(num_clusters=num_clusters, threshold=threshold))
    return np.asarray(c(embs.astype(np.float32)), dtype=int)


def assemble(labels: np.ndarray, sh: int, sm: SegModel, n_samples: int, pairs: np.ndarray, cl: np.ndarray,
             min_on: float, min_off: float, overlap_aware: bool = True) -> Segments:
    """Stage D: clusters -> frame activity -> turns."""
    C, F, S = labels.shape
    if len(pairs) == 0:
        return []
    n_cl = int(cl.max()) + 1
    ratio = sh / sm.rf_shift
    n_frames = int((sm.window + (C - 1) * sh) / sm.rf_shift) + 1
    starts = (np.arange(C) * ratio + 0.5).astype(int)
    # speakers per frame (from the segmentation model itself)
    cnt = np.zeros(n_frames + F)
    num = np.zeros(n_frames + F)
    tot = labels.sum(-1)
    for i in range(C):
        cnt[starts[i]: starts[i] + F] += tot[i]
        num[starts[i]: starts[i] + F] += 1
    spf = ((cnt / np.maximum(num, 1e-12) + 0.5).astype(np.int8))[:n_frames]
    if not overlap_aware:
        spf = np.minimum(spf, 1)
    rel = np.zeros((C, F, n_cl), dtype=np.float32)
    for (i, j), t in zip(pairs, cl):
        rel[i, :, t] = np.maximum(rel[i, :, t], labels[i, :, j])
    acc = np.zeros((n_frames + F, n_cl), dtype=np.float32)
    for i in range(C):
        acc[starts[i]: starts[i] + F] += rel[i]
    acc = acc[:n_frames]
    if n_samples < sm.window or True:
        stop = int(n_samples / sm.rf_shift)
        acc, spf = acc[:stop], spf[:stop]
    order = np.argsort(-acc, axis=1)
    final = np.zeros_like(acc, dtype=bool)
    rank = np.arange(n_cl)[None, :]
    take = rank < np.minimum(spf, n_cl)[:, None]
    np.put_along_axis(final, order, take, axis=1)
    scale = sm.rf_shift / sm.sr
    off = sm.rf_size / sm.sr * 0.5
    out: Segments = []
    for k in range(n_cl):
        fr = final[:, k]
        d = np.diff(np.concatenate([[0], fr.astype(np.int8), [0]]))
        st, en = np.where(d == 1)[0], np.where(d == -1)[0]
        segs = [[a * scale + off, b * scale + off] for a, b in zip(st, en)]
        merged: list[list[float]] = []
        for a, b in segs:
            if merged and a - merged[-1][1] <= min_off:
                merged[-1][1] = b
            else:
                merged.append([a, b])
        out += [(f"spk{k}", a, b) for a, b in merged if b - a >= min_on]
    return sorted(out, key=lambda s: (s[1], s[2]))


@dataclass(frozen=True)
class PCfg:
    seg: str = "pyannote3"
    seg_int8: bool = True
    emb: str = "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k"
    shift: float = 0.1
    exclude_overlap: bool = False
    threshold: float = 0.5
    num_speakers: int = -1
    min_on: float = 0.3
    min_off: float = 0.5
    threads: int = 2


class Prepared:
    """Everything cached for one file / (seg, emb, shift, exclude_overlap)."""

    def __init__(self, item_id: str, audio: np.ndarray, cfg: PCfg, keep_audio: bool = True):
        self.n_samples = len(audio)
        self.audio = audio if keep_audio else None
        self.cfg = cfg
        self.labels, self.sh, self.sm = stage_a(item_id, audio, cfg.seg, cfg.seg_int8, cfg.shift)
        tag = f"{cfg.seg}_{int(cfg.seg_int8)}_{cfg.shift}"
        self.pairs, self.embs = stage_b(item_id, audio, self.labels, self.sh, self.sm, tag, cfg.emb,
                                        cfg.exclude_overlap, cfg.threads)

    def diarize(self, threshold=None, num_speakers=None, min_on=None, min_off=None, cl=None) -> Segments:
        c = self.cfg
        if cl is None:
            cl = cluster_fast(self.embs, c.threshold if threshold is None else threshold,
                              c.num_speakers if num_speakers is None else num_speakers)
        return assemble(self.labels, self.sh, self.sm, self.n_samples, self.pairs, cl,
                        c.min_on if min_on is None else min_on, c.min_off if min_off is None else min_off)
