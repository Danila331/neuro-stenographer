"""ru-dialog-synth: диалоги 2–4 реальных дикторов (VoxForge / RuLS) с моделью очерёдности."""
from __future__ import annotations

import json
from collections import defaultdict

import numpy as np

from . import audio as A
from . import corpora as C
from .common import SR, DatasetWriter, rng_for, stable_split
from .synth import (NoiseBank, Seg, apply_condition, finalize_level, load_noise_bank, plan_dialog, prepare_seg,
                    profile_for, render)

NAME = "ru-dialog-synth"
# доли условий; noise/phone могут быть комбинированы с noise+phone (condition=phone, в detail оба)
COND_WEIGHTS = {
    # meeting: встреча на диктофон в умеренном темпе (farfield — самое «целевое» условие)
    "meeting": {"clean": 1, "noise": 1, "reverb": 1, "overlap": 1, "farfield": 1.5, "phone": 0.5},
    # call: быстрый разговор (звонок): часть файлов с телефонным кодеком
    "call": {"phone": 3, "clean": 1, "noise": 1, "overlap": 1, "reverb": 0.5},
}


def scenario_for(idx: int) -> str:
    return "call" if idx % 2 else "meeting"


def overlap_fraction(utts: list[dict], duration_ms: int) -> float:
    """Доля длительности записи, где одновременно говорят ≥2 реплик."""
    ev = []
    for u in utts:
        ev.append((u["start_ms"], 1))
        ev.append((u["end_ms"], -1))
    ev.sort()
    cnt, last, ov = 0, 0, 0
    for t, d in ev:
        if cnt >= 2:
            ov += t - last
        cnt += d
        last = t
    return round(ov / max(1, duration_ms), 4)


def render_condition(plan, speakers: list[str], cond: str, rng: np.random.Generator, profile: str):
    """Рендер с учётом условий, влияющих на геометрию (reverb / farfield); возвращает (mix, utts, detail)."""
    detail: dict = {"profile": profile}
    gains = {s: float(rng.uniform(-6, 3)) for s in speakers}
    rirs = None
    if cond == "reverb":
        rl, rt60, dim, _ = A.make_rir(rng, len(speakers))
        rirs = dict(zip(speakers, rl))
        detail.update(rt60=round(rt60, 2), room=[round(d, 1) for d in dim])
    elif cond == "farfield":
        # диктофон на столе в переговорной: разные расстояния -> разная громкость (0 … −15 дБ), RT60 0.3–0.8,
        # лёгкий постоянный фон вентиляции (SNR 15–25 дБ)
        rl, rt60, dim, d = A.make_rir(rng, len(speakers), rt60_range=(0.3, 0.8), dist_range=(0.5, 5.0))
        rirs = dict(zip(speakers, rl))
        dmin = min(d)
        base = float(rng.uniform(-3, 0))
        gains = {}
        for s, di in zip(speakers, d):
            g = 0.0 if di == dmin else min(-3.0, max(-15.0, -20 * np.log10(di / dmin)))
            gains[s] = base + g
        detail.update(rt60=round(rt60, 2), room=[round(x, 1) for x in dim], dist_m=[round(x, 2) for x in d],
                      gain_db={s: round(g, 1) for s, g in gains.items()})
    mix, utts = render(plan, rng, gains, rirs, room_noise_db=float(rng.uniform(-58, -48)))
    if cond == "farfield":
        snr = float(rng.uniform(15, 25))
        mix = A.mix_at_snr(mix, A.hvac_noise(len(mix), rng), snr)
        detail.update(hvac_snr_db=round(snr, 1))
    return mix, utts, detail


def pick_condition(rng: np.random.Generator, scenario: str = "meeting") -> str:
    names, p = zip(*COND_WEIGHTS[scenario].items())
    return str(rng.choice(names, p=np.array(p, dtype=float) / sum(p)))


def speaker_pools(clips: list[C.Clip], min_clips: int, max_words: int = 28, min_words: int = 3):
    by = defaultdict(list)
    for c in clips:
        if c.speaker is None:
            continue
        n = len(c.text.split())
        if min_words <= n <= max_words:
            by[c.speaker].append(c)
    return {s: v for s, v in by.items() if len(v) >= min_clips}


def build(n: int = 24, seed: int = 0, name: str = NAME, max_dur_s: float = 300.0, min_dur_s: float = 60.0,
          verbose: bool = True):
    clips = C.load_voxforge() + C.load_ruls()
    pools_all = speaker_pools(clips, min_clips=8)
    if len(pools_all) < 4:
        raise RuntimeError(f"too few speakers with enough clips: {len(pools_all)}")
    spk_ids = sorted(pools_all)
    long_spk = [s for s in spk_ids if s.startswith("ruls_")]

    # babble-пул: реплики случайных дикторов
    brng = rng_for(seed, "babble")
    babble = []
    for s in list(brng.choice(spk_ids, size=min(12, len(spk_ids)), replace=False)):
        c = pools_all[s][int(brng.integers(len(pools_all[s])))]
        try:
            seg = prepare_seg(c.audio(), s, c.text)
            if seg is not None:
                babble.append(seg.audio)
        except Exception:  # noqa: BLE001
            pass
    nb = load_noise_bank(babble)

    w = DatasetWriter(name, extra_cols=["scenario", "condition_detail", "overlap_frac", "speakers_src", "license"])
    made, attempts = 0, 0
    while made < n and attempts < n * 6:
        idx = made
        attempts += 1
        rng = rng_for(seed, name, idx, attempts)
        scenario = scenario_for(idx)
        cond = pick_condition(rng, scenario)
        speed = float(rng.uniform(1.1, 1.25)) if scenario == "call" else 1.0
        n_spk = int(rng.choice([2, 3, 4], p=[0.5, 0.3, 0.2]))
        target = float(rng.uniform(min_dur_s, max_dur_s))
        # выбор дикторов: для длинных диалогов обязательно хотя бы один диктор с большим пулом (RuLS)
        chosen: list[str] = []
        if target > 140 and long_spk:
            chosen.append(str(rng.choice(long_spk)))
        while len(chosen) < n_spk:
            s = str(rng.choice(spk_ids))
            # разные дикторы: исключаем один VoxForge-username
            if s not in chosen:
                chosen.append(s)
        # перемешиваем, чтобы первым говорил не всегда «длинный» диктор
        rng.shuffle(chosen)
        pools: dict[str, list[Seg]] = {}
        for s in chosen:
            cl = list(pools_all[s])
            rng.shuffle(cl)
            cap = 14 if s.startswith("vf_") else 40
            segs = []
            for c in cl[: cap * 2]:
                try:
                    seg = prepare_seg(c.audio(), s, c.text, src=c.source, cid=c.id,
                                      speed=speed * float(rng.uniform(0.98, 1.02)) if speed != 1.0 else 1.0)
                except Exception:  # noqa: BLE001
                    seg = None
                if seg is not None and seg.dur_ms <= 16000:
                    segs.append(seg)
                if len(segs) >= cap:
                    break
            pools[s] = segs
        profile = profile_for(scenario, cond)
        plan = plan_dialog(rng, pools, target, profile=profile, min_s=min_dur_s)
        if plan is None:
            continue
        mix, utts, detail = render_condition(plan, chosen, cond, rng, profile)
        if scenario == "call":
            detail["speed"] = round(speed, 3)
        if cond == "noise" or (cond in ("reverb",) and rng.random() < 0.15):
            mix = apply_condition(mix, [], "noise", rng, nb, detail)
        elif cond == "phone":
            both = rng.random() < 0.3
            mix = apply_condition(mix, [], "noise+phone" if both else "phone", rng, nb, detail)
        mix = finalize_level(mix)
        did = f"synth_{idx:04d}_{scenario}_{cond}"
        # реальные номера дикторов для статистики
        srcs = sorted({s for s in chosen})
        lic = sorted({("GPL-3.0 VoxForge" if s.startswith("vf_") else "PD LibriVox/RuLS") for s in chosen})
        w.add(did, mix, utts, num_speakers=len({u["speaker_id"] for u in utts}), source="real_speech_synth",
              condition=cond, split=stable_split(did), scenario=scenario, condition_detail=json.dumps(detail, ensure_ascii=False),
              overlap_frac=overlap_fraction(utts, len(mix) * 1000 // SR), speakers_src=";".join(srcs),
              license="|".join(lic))
        made += 1
        if verbose:
            print(f"[synth] {did} spk={len(chosen)} dur={len(mix)/SR:.0f}s utts={len(utts)} detail={detail}", flush=True)
    return w.finalize()
