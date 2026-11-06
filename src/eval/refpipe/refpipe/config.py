"""Tier config loading (config/tiers/<tier>.json), `--override a.b.c=value`, config hash."""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
from typing import Any

# Defaults for fields missing in a tier file (ARCHITECTURE §3: absent -> default from code).
DEFAULTS: dict[str, Any] = {
    "tier": "custom",
    "num_threads": 4,
    "audio": {"sample_rate": 16000, "denoise": {"enabled": False, "model": None, "snr_threshold_db": 12.0},
              "normalize_gain": {"enabled": False, "mode": "peak", "peak_dbfs": -1.0, "max_gain_db": 40.0,
                                 "skip_if_peak_below_dbfs": -85.0}},
    "vad": {"model": "silero-vad/silero_vad.onnx", "threshold": 0.45, "min_silence_ms": 300,
            "min_speech_ms": 120, "pad_ms": 150, "max_speech_s": 25.0},
    "asr": {
        "engine": "gigaam_rnnt", "model_dir": "asr/gigaam-v3-rnnt-int8", "decoding": "greedy_search",
        "max_active_paths": 4, "hotwords_file": None, "hotwords_score": 1.5,
        "chunk": {"max_s": 25.0, "min_s": 1.0, "overlap_ms": 0},
        "second_pass": None,
        # refpipe extras (not in the architect's files; forward-compatible, Kotlin should mirror):
        "batch_size": 1,          # chunks per decode call (1 = same as the Android per-chunk call)
        "word_tail_ms": 40,       # end of a word = last token start + tail (clipped by next word start)
        "word_start_offset_ms": 0,  # shift of all token timestamps (calibrate on aligned data)
        "language": "ru",         # whisper only
    },
    "diarization": {
        "segmentation": "diar/pyannote-segmentation-3.0/model.int8.onnx",
        "embedding": "diar/embedding/model.onnx",
        "clustering": {"method": "fast_clustering", "threshold": 0.6, "num_speakers": None, "max_speakers": 8},
        "min_duration_on_s": 0.2, "min_duration_off_s": 0.3,
        "window_shift_ratio": 0.1,  # extra: pyannote window hop / window (0.1 = sherpa default; 0.5 = ~5x faster)
        "num_threads": None,      # extra: None -> top-level num_threads
        "enabled": True,          # extra: false -> single speaker, no diarization (ablation)
    },
    # language: refpipe/Android read it as in ARCHITECTURE (auto | ru | en); without lid_model or asr_en -> ru
    "language": {"mode": "auto", "lid_model": None, "lid_seconds": 10.0, "per_chunk": False,
                 "switch_min_ms": 4000},  # switch_min_ms: refpipe extra (hysteresis, see lid.smooth_languages)
    "postprocess": {"numbers": "as_is", "drop_tiny_chunks": {"enabled": False, "max_chars": 2}},
    "asr_en": None,  # same structure as "asr"
    "align": {"max_gap_ms": 1500, "min_island_words": 1, "min_island_ms": 400},
    "punctuation": {"source": "none", "model": None},
    "llm": {"model": None, "n_ctx": 4096, "n_threads": 4, "gpu_layers": 0},
    "normalization": {"numbers": "as_is", "yo_to_e": False, "case": "as_is"},
}


def deep_merge(base: dict, over: dict) -> dict:
    out = copy.deepcopy(base)
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = deep_merge(out[k], v)
        else:
            out[k] = copy.deepcopy(v)
    return out


def load_config(path: str | Path) -> dict:
    return deep_merge(DEFAULTS, json.loads(Path(path).read_text(encoding="utf-8")))


def parse_value(s: str) -> Any:
    try:
        return json.loads(s)
    except json.JSONDecodeError:
        return s


def apply_overrides(cfg: dict, overrides: list[str] | None) -> dict:
    """`key.sub=value` (value parsed as JSON, otherwise a plain string; `null` -> None)."""
    cfg = copy.deepcopy(cfg)
    for item in overrides or []:
        if "=" not in item:
            raise ValueError(f"bad --override {item!r}, expected key.path=value")
        key, val = item.split("=", 1)
        cur = cfg
        parts = key.strip().split(".")
        for p in parts[:-1]:
            if not isinstance(cur.get(p), dict):
                cur[p] = {}
            cur = cur[p]
        cur[parts[-1]] = parse_value(val)
    return cfg


def canonical(cfg: dict) -> str:
    def strip(o):
        if isinstance(o, dict):
            return {k: strip(v) for k, v in o.items() if not k.startswith("_")}
        if isinstance(o, list):
            return [strip(v) for v in o]
        return o

    return json.dumps(strip(cfg), sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def config_hash(cfg: dict) -> str:
    """sha256 of the canonical config (sorted keys, no `_*` comment keys) -- after overrides."""
    return hashlib.sha256(canonical(cfg).encode("utf-8")).hexdigest()
