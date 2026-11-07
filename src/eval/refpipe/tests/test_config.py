import json
from pathlib import Path

from refpipe.config import apply_overrides, config_hash, load_config

TIERS = Path(__file__).resolve().parents[4] / "config" / "tiers"


def test_override_nested_and_types():
    cfg = load_config(TIERS / "medium.json")
    o = apply_overrides(cfg, ["diarization.clustering.threshold=0.7", "asr.decoding=modified_beam_search",
                              "asr.second_pass=null", "num_threads=2", "vad.pad_ms=100"])
    assert o["diarization"]["clustering"]["threshold"] == 0.7
    assert o["asr"]["decoding"] == "modified_beam_search" and o["asr"]["second_pass"] is None
    assert o["num_threads"] == 2 and o["vad"]["pad_ms"] == 100
    assert cfg["num_threads"] == 4  # original untouched


def test_hash_ignores_comment_keys_and_changes_with_overrides():
    cfg = load_config(TIERS / "medium.json")
    h = config_hash(cfg)
    assert h == config_hash({**cfg, "_note": "other"})
    assert h != config_hash(apply_overrides(cfg, ["align.max_gap_ms=1000"]))
    assert len(h) == 64


def test_all_tier_files_load():
    for t in ("lite", "medium", "hard"):
        assert load_config(TIERS / f"{t}.json")["tier"] == t


def test_hash_ignores_live_and_llm_blocks():
    cfg = load_config(TIERS / "medium.json")
    h = config_hash(cfg)
    assert h == config_hash({**cfg, "live": {"enabled": False}, "llm": {"model": "other.gguf"}})
    other = round(float(cfg["asr"].get("blank_penalty", 0.0)) + 0.37, 2)  # always differs from the config value
    assert h != config_hash(apply_overrides(cfg, [f"asr.blank_penalty={other}"]))
