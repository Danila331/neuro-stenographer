"""ru-dialog-tts-cats: сценарии «котиков», озвученные локальным TTS (Piper ru_RU; macOS `say` Milena, если есть)."""
from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import soundfile as sf
import soxr

from . import audio as A
from .build_synth import overlap_fraction, overlap_transitions, pick_condition, render_condition, scenario_for
from .common import SR, DatasetWriter, raw_root, rng_for, stable_split
from .synth import (GAP_PROFILES, DialogPlan, Seg, apply_condition, finalize_level, load_noise_bank, prepare_seg,
                    profile_for, sample_gap)

NAME = "ru-dialog-tts-cats"
DATA_DIR = Path(__file__).parent / "data"


@dataclass
class Line:
    spk: int           # 1..N
    mode: str          # normal | interrupt | quick
    text: str


@dataclass
class Scenario:
    id: str
    title: str
    n_spk: int
    lines: list[Line]


def parse_scenarios(paths: list[Path] | None = None) -> list[Scenario]:
    paths = paths or sorted(DATA_DIR.glob("cats_ru_*.txt"))
    out: list[Scenario] = []
    cur: Scenario | None = None
    for p in paths:
        for raw in p.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") and not line.startswith("##"):
                continue
            if line.startswith("##"):
                parts = [x.strip() for x in line[2:].split("|")]
                cur = Scenario(id=parts[0], title=parts[1], n_spk=int(parts[2]), lines=[])
                out.append(cur)
                continue
            m = re.match(r"^(\d)([!+]?):\s*(.+)$", line)
            if not m or cur is None:
                raise ValueError(f"bad line in {p}: {raw!r}")
            mode = {"": "normal", "!": "interrupt", "+": "quick"}[m.group(2)]
            cur.lines.append(Line(int(m.group(1)), mode, m.group(3).strip()))
    for sc in out:
        assert {l.spk for l in sc.lines} <= set(range(1, sc.n_spk + 1)), sc.id
    return out


class Voices:
    """Piper-голоса (+ macOS say Milena). synth(voice, text, length_scale, noise_scale, pitch) -> float32 16 кГц."""

    def __init__(self, use_say: bool = True):
        from piper import PiperVoice, SynthesisConfig
        self._SC = SynthesisConfig
        root = raw_root() / "piper"
        self.piper = {}
        for onnx in sorted(root.glob("*.onnx")):
            name = onnx.name.split("-")[1]  # ru_RU-denis-medium.onnx -> denis
            self.piper[name] = PiperVoice.load(str(onnx), str(onnx) + ".json")
        self.say = use_say and sys.platform == "darwin" and shutil.which("say") is not None
        self.names = sorted(self.piper) + (["milena"] if self.say else [])
        if not self.names:
            raise RuntimeError("no TTS voices found; run `python -m stenodata.fetch piper`")

    def synth(self, voice: str, text: str, length_scale: float = 1.0, noise_scale: float = 0.667, pitch: float = 1.0):
        if voice == "milena":
            with tempfile.TemporaryDirectory() as td:
                f = Path(td) / "x.wav"
                rate = int(round(175 / length_scale))
                subprocess.run(["say", "-v", "Milena", "-r", str(rate), "-o", str(f), "--data-format=LEI16@22050", text],
                               check=True, capture_output=True)
                x, sr = sf.read(str(f), dtype="float32")
        else:
            v = self.piper[voice]
            cfg = self._SC(length_scale=length_scale * pitch, noise_scale=noise_scale, noise_w_scale=0.8)
            chunks = list(v.synthesize(text, syn_config=cfg))
            x = np.concatenate([c.audio_float_array for c in chunks]).astype(np.float32)
            sr = v.config.sample_rate
        if x.ndim > 1:
            x = x.mean(1)
        # pitch>1: длиннее синтез (length_scale*pitch), потом ускоряем ресемплингом -> выше тон при том же темпе
        sr_eff = int(sr * pitch)
        return soxr.resample(x, sr_eff, SR, quality="HQ").astype(np.float32)


def build_plan(sc: Scenario, segs: list[Seg], rng: np.random.Generator, cond: str, scenario: str) -> DialogPlan:
    prof = GAP_PROFILES[profile_for(scenario, cond)]
    call = scenario == "call"
    gaps = []
    for i, (ln, seg) in enumerate(zip(sc.lines, segs)):
        if i == 0:
            gaps.append(0)
            continue
        prev = segs[i - 1]
        same = sc.lines[i - 1].spk == ln.spk
        mode = ln.mode
        if call and mode == "normal" and not same and rng.random() < 0.2:
            mode = "interrupt"     # в звонках перебиваний больше (суммарно ~25 %)
        if mode == "interrupt" and not same:
            d = int(rng.integers(250, 750 if cond != "overlap" else 1300) * (0.7 if call else 1.0))
            g = -min(d, int(0.6 * min(prev.dur_ms, seg.dur_ms)))
        elif mode == "quick" and not same:
            g = int(rng.integers(20, 120 if call else 220))
        else:
            g = sample_gap(rng, same, prof, prev.dur_ms, seg.dur_ms)
            if cond != "overlap" and not call and g < 0 and rng.random() < 0.3:
                g = int(rng.integers(100, 400))   # у сценария свои перебивания — не задваиваем
        gaps.append(g)
    return DialogPlan(order=segs, gaps_ms=gaps)


def build(n: int = 40, seed: int = 0, name: str = NAME, verbose: bool = True, use_say: bool = True):
    scs = parse_scenarios()
    voices = Voices(use_say=use_say)
    babble = []
    nb = None
    w = DatasetWriter(name, extra_cols=["scenario", "condition_detail", "overlap_frac", "overlap_trans", "voices", "script", "license"])
    cache: dict = {}
    for idx in range(n):
        sc = scs[idx % len(scs)]
        variant = idx // len(scs)
        split_key = sc.id
        if variant >= 1:
            # длинная «сессия»: сценарий + следующий с тем же числом говорящих (≈ 1–3 мин)
            j = (idx + 1) % len(scs)
            for k in range(len(scs)):
                nxt = scs[(idx + 1 + k) % len(scs)]
                if nxt.n_spk == sc.n_spk and nxt.id != sc.id:
                    break
            sc = Scenario(id=f"{sc.id}+{nxt.id}", title=f"{sc.title} / {nxt.title}", n_spk=sc.n_spk,
                          lines=sc.lines + nxt.lines)
            split_key = "test" if "test" in (stable_split(sc.id.split("+")[0]), stable_split(nxt.id)) else "dev"
        rng = rng_for(seed, name, idx)
        scenario = scenario_for(idx + variant)
        cond = pick_condition(rng, scenario)
        speed = float(rng.uniform(1.1, 1.25)) if scenario == "call" else 1.0
        # голоса: разные базовые голоса на разных говорящих
        vs = [str(v) for v in rng.permutation(voices.names)]
        while len(vs) < sc.n_spk:
            vs.append(vs[len(vs) % len(voices.names)])
        spk_voice = {k: vs[k - 1] for k in range(1, sc.n_spk + 1)}
        spk_ls = {k: float(rng.uniform(0.88, 1.18)) for k in spk_voice}
        spk_pitch = {k: float(rng.uniform(0.9, 1.22)) if rng.random() < 0.7 else 1.0 for k in spk_voice}
        segs = []
        for ln in sc.lines:
            key = (spk_voice[ln.spk], ln.text, round(spk_ls[ln.spk], 3), round(spk_pitch[ln.spk], 3), variant)
            if key not in cache:
                cache[key] = voices.synth(spk_voice[ln.spk], ln.text, spk_ls[ln.spk], float(rng.uniform(0.5, 0.85)), spk_pitch[ln.spk])
            seg = prepare_seg(cache[key], f"speaker_{ln.spk}", ln.text, src="tts", speed=speed, min_ms=80)
            if seg is None:
                raise RuntimeError(f"empty TTS for {ln.text!r}")
            segs.append(seg)
        plan = build_plan(sc, segs, rng, cond, scenario)
        speakers = [f"speaker_{k}" for k in spk_voice]
        mix, utts, detail = render_condition(plan, speakers, cond, rng, profile_for(scenario, cond))
        if scenario == "call":
            detail["speed"] = round(speed, 3)
        if nb is None:
            nb = load_noise_bank([s.audio for s in segs[:6]])
        if cond == "noise":
            mix = apply_condition(mix, [], "noise", rng, nb, detail)
        elif cond == "phone":
            mix = apply_condition(mix, [], "noise+phone" if rng.random() < 0.3 else "phone", rng, nb, detail)
        mix = finalize_level(mix)
        did = f"cats_{sc.id.replace('+', '-')}_v{variant}_{scenario}_{cond}"
        w.add(did, mix, utts, num_speakers=len({u["speaker_id"] for u in utts}), source="tts", condition=cond,
              split=(split_key if split_key in ('dev', 'test') else stable_split(split_key)), scenario=scenario, condition_detail=json.dumps(detail, ensure_ascii=False),
              overlap_frac=overlap_fraction(utts, len(mix) * 1000 // SR),
              overlap_trans=overlap_transitions(utts), voices=";".join(f"{k}:{spk_voice[k]}@p{spk_pitch[k]:.2f}" for k in spk_voice), script=sc.id,
              license="piper-voices ru_RU (HF rhasspy/piper-voices)" + ("; macOS say Milena (local only)" if "milena" in spk_voice.values() else ""))
        if verbose:
            print(f"[tts] {did} spk={sc.n_spk} dur={len(mix)/SR:.0f}s utts={len(utts)} voices={spk_voice}", flush=True)
    return w.finalize()
