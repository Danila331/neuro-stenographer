"""Сквозные тесты сборки на фиктивном корпусе (без сети и без кэша)."""
import csv
import hashlib
import json

import numpy as np
import pytest
import soundfile as sf

from stenodata import build_synth as BS
from stenodata import corpora as C
from stenodata.common import SR
from stenodata.validate import validate_dataset
from test_formats import _fake_speech  # noqa: E402


def _fake_clips(tmp_path, n_ru=6, n_en=4, per=14):
    rng = np.random.default_rng(0)
    clips = []
    for k in range(n_ru + n_en):
        spk = f"vf_u{k}" if k < n_ru else f"ls_{k}"
        for j in range(per):
            x = np.concatenate([np.zeros(int(0.3 * SR), np.float32), _fake_speech(rng, rng.uniform(2, 5), 90 + 25 * k),
                                np.zeros(int(0.3 * SR), np.float32)])
            p = tmp_path / "wav" / f"{spk}_{j}.wav"
            p.parent.mkdir(exist_ok=True)
            sf.write(str(p), x, SR)
            text = "раз два три четыре пять" if k < n_ru else "one two three four five six"
            clips.append(C.Clip(id=f"{spk}_{j}", path=p, text=text, speaker=spk, source="fake", ref_style="lower_nopunct"))
    return clips


@pytest.fixture()
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("STENO_DATA", str(tmp_path / "data"))
    monkeypatch.setenv("STENO_RAW", str(tmp_path / "raw"))
    (tmp_path / "raw").mkdir()
    return tmp_path


def test_build_dialogs_deterministic_and_valid(env, monkeypatch):
    clips = _fake_clips(env)
    kw = dict(lang="ru", min_dur_s=20, max_dur_s=40, verbose=False, min_clips=5)
    root1 = BS.build_dialogs(clips, 4, 7, "t", "t", **kw)
    assert validate_dataset(root1) == []
    rows = list(csv.DictReader(open(root1 / "manifest.csv", encoding="utf-8")))
    assert {r["scenario"] for r in rows} == {"call", "meeting"}
    assert all(r["lang"] == "ru" for r in rows)
    h = lambda root: {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((root / "audio").glob("*.wav"))}  # noqa: E731
    h1 = h(root1)
    monkeypatch.setenv("STENO_DATA", str(env / "data2"))
    root2 = BS.build_dialogs(clips, 4, 7, "t", "t", **kw)
    assert root2 != root1 and h(root2) == h1                  # тот же seed -> побайтно те же файлы
    monkeypatch.setenv("STENO_DATA", str(env / "data3"))
    root3 = BS.build_dialogs(clips, 4, 8, "t", "t", **kw)
    assert h(root3) != h1                                     # другой seed -> другие


def test_mixed_has_both_languages(env):
    clips = _fake_clips(env)

    def chooser(pools_all):
        ru = sorted(s for s in pools_all if BS.lang_of_speaker(s) == "ru")
        en = sorted(s for s in pools_all if BS.lang_of_speaker(s) == "en")
        return lambda rng, n, t: [str(x) for x in rng.choice(ru, n - 1, replace=False)] + [str(rng.choice(en))]

    root = BS.build_dialogs(clips, 2, 1, "mx", "mix", lang="ru+en", chooser=chooser, mixed=True, min_dur_s=20, max_dur_s=40,
                            fixed_n_spk=[3], verbose=False, min_clips=5, cond_weights={"meeting": {"clean": 1}, "call": {"clean": 1}})
    assert validate_dataset(root) == []
    for r in csv.DictReader(open(root / "manifest.csv", encoding="utf-8")):
        langs = {s["lang"] for s in json.loads(r["lang_segments"])}
        assert langs == {"ru", "en"} and r["lang"] == "ru+en"


def test_overlap_transitions():
    u = [{"speaker_id": "a", "start_ms": 0, "end_ms": 1000, "text": "x"},
         {"speaker_id": "b", "start_ms": 900, "end_ms": 2000, "text": "x"},
         {"speaker_id": "a", "start_ms": 2500, "end_ms": 3000, "text": "x"}]
    assert BS.overlap_transitions(u) == 0.5
    assert BS.overlap_fraction(u, 3000) == pytest.approx(100 / 3000, abs=1e-3)


def test_ref_style():
    assert C.ref_style_of("Привет, мир.") == "punct_case"
    assert C.ref_style_of("привет мир") == "lower_nopunct"
    assert C.ref_style_of("HELLO WORLD") == "upper_nopunct"
