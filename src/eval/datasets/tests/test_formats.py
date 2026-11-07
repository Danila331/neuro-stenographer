"""Тесты формата: ref по правилам docs/team/ARCHITECTURE_TEAM.md §1, аудио 16 кГц mono, времена в пределах длительности."""
import json

import numpy as np
import pytest
import soundfile as sf

from stenodata import audio as A
from stenodata import synth as S
from stenodata.common import SR, DatasetWriter, data_root, rng_for
from stenodata.validate import check_ref, validate_dataset

D = 10_000


def ok(utts):
    return check_ref({"transcript": utts}, D)


def u(spk, s, e, t="да"):
    return {"speaker_id": spk, "start_ms": s, "end_ms": e, "text": t}


def test_ref_valid():
    assert ok([u("speaker_1", 0, 500), u("speaker_2", 400, 900), u("speaker_1", 1000, 2000)]) == []
    assert ok([]) == []


@pytest.mark.parametrize("utts", [
    [u("speaker_2", 0, 500)],                                   # нумерация не с 1
    [u("speaker_1", 0, 500), u("speaker_3", 600, 700)],         # пропуск номера
    [u("speaker_1", 500, 500)],                                 # start == end
    [u("speaker_1", 0, D + 1)],                                 # за пределами длительности
    [u("speaker_1", -1, 5)],                                    # отрицательное время
    [u("speaker_1", 600, 700), u("speaker_1", 0, 100)],         # не отсортировано
    [u("speaker_1", 0, 100, " да")],                            # пробелы
    [u("speaker_1", 0, 100, "")],                               # пустой текст
    [u("spk1", 0, 100)],                                        # плохой id
    [{"speaker_id": "speaker_1", "start_ms": 0.5, "end_ms": 5, "text": "а"}],  # не int
    [{"speaker_id": "speaker_1", "start_ms": 0, "end_ms": 5, "text": "а", "extra": 1}],  # лишний ключ
])
def test_ref_invalid(utts):
    assert ok(utts) != []


def test_ref_top_level():
    assert check_ref({"transcript": [], "x": 1}, D) != []
    assert check_ref([], D) != []


def _fake_speech(rng, dur_s, f0=150.0):
    """Псевдо-речь: слоговые всплески гармонического сигнала с шумовыми паузами; для энергетического VAD."""
    n = int(dur_s * SR)
    t = np.arange(n) / SR
    env = np.clip(np.sin(2 * np.pi * 3.5 * t), 0, None) ** 0.5
    x = sum(np.sin(2 * np.pi * f0 * k * t) / k for k in range(1, 8)) * env
    x += 0.003 * rng.standard_normal(n)
    return (0.3 * x / (np.abs(x).max() + 1e-9)).astype(np.float32)


def test_trim_silence_bounds():
    rng = np.random.default_rng(0)
    sp = _fake_speech(rng, 2.0)
    x = np.concatenate([0.001 * rng.standard_normal(SR), sp, 0.001 * rng.standard_normal(SR)]).astype(np.float32)
    y, s, e = A.trim_silence(x)
    assert abs(s - SR) < 0.25 * SR and abs(e - 3 * SR) < 0.25 * SR
    assert len(y) < len(x)


def _pools(rng):
    return {f"s{i}": [S.prepare_seg(_fake_speech(rng, rng.uniform(2, 5), 100 + 40 * i), f"s{i}", f"реплика {i} {j}")
                      for j in range(12)] for i in range(3)}


@pytest.mark.parametrize("cond", ["clean", "reverb", "farfield", "overlap", "noise", "phone"])
@pytest.mark.parametrize("scenario", ["meeting", "call"])
def test_dialog_all_conditions(tmp_path, monkeypatch, cond, scenario):
    from stenodata.build_synth import overlap_fraction, render_condition
    monkeypatch.setenv("STENO_DATA", str(tmp_path))
    rng = rng_for(1, cond, scenario)
    pools = _pools(rng)
    prof = S.profile_for(scenario, cond)
    plan = S.plan_dialog(rng, pools, target_s=40, profile=prof, min_s=20)
    assert plan is not None
    mix, utts, detail = render_condition(plan, list(pools), cond, rng, prof)
    nb = S.NoiseBank(babble_pool=[pools["s0"][0].audio])
    if cond == "noise":
        mix = S.apply_condition(mix, [], "noise", rng, nb, detail)
        assert "snr_db" in detail and 5 <= detail["snr_db"] <= 20
    if cond == "phone":
        mix = S.apply_condition(mix, [], "phone", rng, nb, detail)
    mix = S.finalize_level(mix)
    assert np.isfinite(mix).all() and np.abs(mix).max() <= 1.0
    if cond == "farfield":
        assert 15 <= detail["hvac_snr_db"] <= 25 and 0.3 <= detail["rt60"] <= 0.8
        g = sorted(detail["gain_db"].values())
        assert g[-1] - g[0] <= 15.0 + 3.0 + 1e-6    # разброс громкости ≤ ~18 дБ (база −3…0 плюс 0…−15)
    w = DatasetWriter("t")
    w.add("a", mix, utts, num_speakers=len({x["speaker_id"] for x in utts}), source="t", condition=cond, split="dev")
    root = w.finalize()
    assert validate_dataset(root) == []
    # времена внутри длительности, аудио 16 кГц mono PCM16
    info = sf.info(str(root / "audio" / "a.wav"))
    assert (info.samplerate, info.channels, info.subtype) == (16000, 1, "PCM_16")
    ref = json.loads((root / "ref" / "a.json").read_text(encoding="utf-8"))["transcript"]
    assert max(x["end_ms"] for x in ref) <= info.frames * 1000 / SR + 1
    if cond != "overlap" and scenario == "meeting":
        assert overlap_fraction(ref, 1000) >= 0


def test_alignment_of_refs_with_audio(tmp_path):
    """Речь в аудио совпадает с ref по времени (нет сдвигов от реверберации/кодека)."""
    from stenodata.build_synth import render_condition
    rng = rng_for(5, "align")
    for cond in ("clean", "reverb", "farfield"):
        plan = S.plan_dialog(rng, _pools(rng), target_s=40, profile="normal", min_s=20)
        mix, utts, _ = render_condition(plan, ["s0", "s1", "s2"], cond, rng, "normal")
        m = A.speech_mask(mix)
        ref_m = np.zeros(len(m), bool)
        for x in utts:
            ref_m[x["start_ms"] // 10: x["end_ms"] // 10] = True
        assert (m & ~ref_m).sum() / max(1, m.sum()) < 0.08, cond


def test_phone_channel_keeps_length_and_timing():
    rng = np.random.default_rng(2)
    x = np.concatenate([np.zeros(SR, np.float32), _fake_speech(rng, 2.0), np.zeros(SR, np.float32)])
    for codec in A.PHONE_CODECS:
        y = A.phone_channel(x, codec)
        assert len(y) == len(x)
        m = A.speech_mask(y)
        idx = np.flatnonzero(m)
        assert abs(idx[0] * 10 - 1000) < 250 and abs(idx[-1] * 10 - 3000) < 300, codec


def test_time_stretch_speeds_up():
    rng = np.random.default_rng(3)
    x = _fake_speech(rng, 3.0)
    y = A.time_stretch(x, 1.2)
    assert abs(len(y) - len(x) / 1.2) < 0.03 * SR


def test_rir_direct_path_aligned():
    rirs, rt60, dim, dists = A.make_rir(np.random.default_rng(4), 3, rt60_range=(0.3, 0.8))
    for h in rirs:
        assert int(np.argmax(np.abs(h))) == 0
        assert abs(np.sum(h ** 2) - 1.0) < 1e-6
    assert len(dists) == 3


def test_gap_distribution_overlap_share():
    rng = np.random.default_rng(7)
    prof = S.GAP_PROFILES["normal"]
    g = np.array([S.sample_gap(rng, False, prof, 4000, 4000) for _ in range(4000)])
    assert 0.09 < (g < 0).mean() < 0.16
    assert g.min() >= -400 and g.max() <= 900
    prof = S.GAP_PROFILES["call"]
    g = np.array([S.sample_gap(rng, False, prof, 4000, 4000) for _ in range(4000)])
    assert 0.2 < (g < 0).mean() < 0.3 and np.median(g[g > 0]) < 350


@pytest.mark.parametrize("name", ["ru-asr-dev", "ru-dialog-synth", "ru-dialog-tts-cats", "en-asr-dev", "en-dialog-synth", "ru-en-mixed", "real"])
def test_built_datasets_in_cache(name):
    """Проверка собранных датасетов в общем кэше (пропускается, если ещё не собраны)."""
    root = data_root() / name
    if not (root / "manifest.csv").exists():
        pytest.skip(f"{name} not built")
    assert validate_dataset(root) == []
