import json

import numpy as np
import pytest
import soundfile as sf

from stenodata import real
from stenodata.common import SR
from stenodata.validate import validate_dataset


def test_real_roundtrip(tmp_path, monkeypatch):
    monkeypatch.setenv("STENO_DATA", str(tmp_path / "data"))
    src = tmp_path / "Встреча 1.wav"
    sf.write(str(src), (0.1 * np.random.default_rng(0).standard_normal(44100 * 3)).astype("float32"), 44100)
    ids = real.import_audio([src], split="dev", num_speakers=2)
    assert ids == ["Встреча_1"]
    root = tmp_path / "data" / "real"
    assert validate_dataset(root) == []              # неразмеченная запись допустима (annotated=0)
    info = sf.info(str(root / "audio" / "Встреча_1.wav"))
    assert (info.samplerate, info.channels) == (16000, 1)

    hyp = tmp_path / "hyp.json"
    hyp.write_text(json.dumps({"transcript": [
        {"speaker_id": "speaker_2", "start_ms": 100, "end_ms": 1500, "text": "Добрый день!"},
        {"speaker_id": "speaker_1", "start_ms": 1600, "end_ms": 2800, "text": "Здравствуйте."}]}), encoding="utf-8")
    txt = real.hypothesis_to_draft(hyp, "Встреча_1")
    body = txt.read_text(encoding="utf-8")
    assert "[00:00.100 - 00:01.500] speaker_1: Добрый день!" in body    # перенумерация по первому появлению
    txt.write_text(body.replace("speaker_2: Здравствуйте.", "Иван: Здравствуйте, коллеги."), encoding="utf-8")
    ref = real.compile_ref("Встреча_1")
    obj = json.loads(ref.read_text(encoding="utf-8"))
    assert [u["speaker_id"] for u in obj["transcript"]] == ["speaker_1", "speaker_2"]
    assert obj["transcript"][1]["text"] == "Здравствуйте, коллеги."
    assert validate_dataset(root) == []


def test_annotation_errors(tmp_path):
    with pytest.raises(ValueError):
        real.parse_annotation("not a line")
    u = real.parse_annotation("[01:02.5 - 01:03] A: привет\n# comment\n")
    assert u == [{"speaker_id": "A", "start_ms": 62500, "end_ms": 63000, "text": "привет"}]
