"""End-to-end on a real Russian wav; skipped if models / sample are not in the shared cache."""
import json
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[6]
MODELS = ROOT / ".cache" / "models"
SAMPLE = ROOT / ".cache" / "data" / "refpipe-smoke" / "example.wav"
CFG = Path(__file__).resolve().parents[4] / "config" / "tiers" / "medium.json"
NEED = [MODELS / "asr/gigaam-v3-rnnt-punct-int8/encoder.int8.onnx", MODELS / "silero-vad/silero_vad.onnx",
        MODELS / "diar/pyannote-segmentation-3.0/model.int8.onnx", MODELS / "diar/embedding/model.onnx"]

pytestmark = pytest.mark.skipif(not (SAMPLE.exists() and all(p.exists() for p in NEED)), reason="models/sample missing")


def test_medium_end_to_end_valid_contract(tmp_path):
    import jsonschema
    from refpipe.cli import main

    rc = main(["run", "--config", str(CFG), "--models", str(MODELS), "--input", str(SAMPLE), "--out", str(tmp_path),
               "--override", "language.mode=ru"])
    assert rc == 0
    out = json.loads((tmp_path / "example.json").read_text(encoding="utf-8"))
    schema = json.loads((CFG.parents[1] / "transcript.schema.json").read_text())
    jsonschema.validate(out, schema)
    text = " ".join(u["text"] for u in out["transcript"])
    assert "лукоморья" in text.lower() and "дуб" in text.lower() and "," in text  # asr_native punctuation
    assert "\\u" not in (tmp_path / "example.json").read_text(encoding="utf-8")
    meta = json.loads((tmp_path / "example.meta.json").read_text())
    assert {"processing_ms", "stage_ms", "tier", "config_hash", "num_threads", "models", "platform"} <= set(meta)
    assert out["transcript"][-1]["end_ms"] <= 11290
