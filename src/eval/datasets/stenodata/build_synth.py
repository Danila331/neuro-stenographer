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


def overlap_transitions(utts: list[dict]) -> float:
    """Доля смен говорящего, при которых следующая реплика начинается до конца предыдущей."""
    u = sorted(utts, key=lambda x: (x["start_ms"], x["end_ms"]))
    sw = [(a, b) for a, b in zip(u, u[1:]) if a["speaker_id"] != b["speaker_id"]]
    return round(sum(b["start_ms"] < a["end_ms"] for a, b in sw) / len(sw), 3) if sw else 0.0


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


def plan_conditions(seed: int, name: str, scenarios: list[str], weights: dict | None = None) -> list[str]:
    """Детерминированное сбалансированное распределение условий по файлам внутри каждого сценария
    (метод наибольших остатков по весам), затем перемешивание."""
    weights = weights or COND_WEIGHTS
    out = [""] * len(scenarios)
    for sc in sorted(set(scenarios)):
        pos = [i for i, x in enumerate(scenarios) if x == sc]
        names = list(weights[sc])
        w = np.array([weights[sc][k] for k in names], dtype=float)
        w /= w.sum()
        raw = w * len(pos)
        cnt = np.floor(raw).astype(int)
        order = np.argsort(-(raw - cnt), kind="stable")
        for k in order[: len(pos) - cnt.sum()]:
            cnt[k] += 1
        seq = [nm for nm, c in zip(names, cnt) for _ in range(int(c))]
        rng = rng_for(seed, name, "cond", sc)
        rng.shuffle(seq)
        for i, c in zip(pos, seq):
            out[i] = c
    return out


def pick_condition(rng: np.random.Generator, scenario: str = "meeting", weights: dict | None = None) -> str:
    names, p = zip(*(weights or COND_WEIGHTS)[scenario].items())
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


# EN: по заданию — clean, noise, farfield (+ phone для call), сценарии call/meeting
COND_WEIGHTS_EN = {
    "meeting": {"clean": 1, "noise": 1, "farfield": 1.5, "overlap": 0.5},
    "call": {"clean": 1, "noise": 1, "phone": 1.5, "overlap": 0.5},
}


def lang_of_speaker(spk: str) -> str:
    return "en" if spk.startswith("ls_") or spk.startswith("fleurs_en") else "ru"


def speaker_license(spk: str) -> str:
    if spk.startswith("vf_"):
        return "GPL-3.0 VoxForge"
    if spk.startswith("ruls_"):
        return "PD LibriVox/RuLS"
    if spk.startswith("ls_"):
        return "CC-BY-4.0 LibriSpeech"
    return "unknown"


def default_chooser(pools_all, long_prefix=("ruls_", "ls_")):
    spk_ids = sorted(pools_all)
    long_spk = [s for s in spk_ids if s.startswith(long_prefix)]
    cap_of = lambda s: 14 if s.startswith("vf_") else 40  # noqa: E731

    def choose(rng, n_spk, target):
        chosen: list[str] = []
        if target > 140 and long_spk and not all(s.startswith(long_prefix) for s in spk_ids) :
            chosen.append(str(rng.choice(long_spk)))
        while len(chosen) < n_spk:
            s = str(rng.choice(spk_ids))
            if s not in chosen:
                chosen.append(s)
        return chosen
    return choose, cap_of


def build_dialogs(clips: list[C.Clip], n: int, seed: int, name: str, id_prefix: str, *, lang: str = "ru",
                  cond_weights: dict | None = None, n_spk_p=(0.5, 0.3, 0.2), min_dur_s: float = 60.0,
                  max_dur_s: float = 300.0, chooser=None, mixed: bool = False, verbose: bool = True,
                  min_clips: int = 8, fixed_n_spk: list[int] | None = None):
    pools_all = speaker_pools(clips, min_clips=min_clips, min_words=2)
    if len(pools_all) < 4:
        raise RuntimeError(f"too few speakers with enough clips: {len(pools_all)}")
    spk_ids = sorted(pools_all)
    choose, cap_of = default_chooser(pools_all)
    if chooser is not None:
        choose = chooser(pools_all)

    brng = rng_for(seed, name, "babble")
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

    extra = ["scenario", "condition_detail", "overlap_frac", "overlap_trans", "speakers_src", "license"] + (["lang_segments"] if mixed else [])
    w = DatasetWriter(name, extra_cols=extra)
    conds = plan_conditions(seed, name, [scenario_for(i) for i in range(n)], cond_weights)
    made, attempts = 0, 0
    while made < n and attempts < n * 6:
        idx = made
        attempts += 1
        rng = rng_for(seed, name, idx, attempts)
        scenario = scenario_for(idx)
        cond = conds[idx]
        speed = float(rng.uniform(1.1, 1.25)) if scenario == "call" else 1.0
        n_spk = int(fixed_n_spk[idx % len(fixed_n_spk)]) if fixed_n_spk else int(rng.choice([2, 3, 4], p=np.array(n_spk_p) / sum(n_spk_p)))
        target = float(rng.uniform(min_dur_s, max_dur_s))
        chosen = choose(rng, n_spk, target)
        rng.shuffle(chosen)
        pools: dict[str, list[Seg]] = {}
        for s in chosen:
            cl = list(pools_all[s])
            rng.shuffle(cl)
            cap = cap_of(s)
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
        did = f"{id_prefix}_{idx:04d}_{scenario}_{cond}"
        row_extra = {}
        if mixed:
            row_extra["lang_segments"] = json.dumps(
                [{"speaker": u["speaker_id"], "start_ms": u["start_ms"], "end_ms": u["end_ms"], "lang": lang_of_speaker(u["speaker_id"])}
                 for u in utts], ensure_ascii=False)
        # speaker_id в utts — исходные ключи дикторов; DatasetWriter переименует в speaker_N
        w.add(did, mix, utts, num_speakers=len({u["speaker_id"] for u in utts}), source="real_speech_synth",
              condition=cond, split=stable_split(did), scenario=scenario, condition_detail=json.dumps(detail, ensure_ascii=False),
              overlap_frac=overlap_fraction(utts, len(mix) * 1000 // SR), overlap_trans=overlap_transitions(utts), speakers_src=";".join(sorted(set(chosen))),
              license="|".join(sorted({speaker_license(s) for s in chosen})), lang=lang, **row_extra)
        made += 1
        if verbose:
            print(f"[{name}] {did} spk={len(chosen)} dur={len(mix)/SR:.0f}s utts={len(utts)} detail={detail}", flush=True)
    return w.finalize()


def build(n: int = 36, seed: int = 0, name: str = NAME, max_dur_s: float = 300.0, min_dur_s: float = 60.0,
          verbose: bool = True):
    clips = C.load_voxforge() + C.load_ruls()
    return build_dialogs(clips, n, seed, name, "synth", lang="ru", min_dur_s=min_dur_s, max_dur_s=max_dur_s, verbose=verbose)


def build_en(n: int = 14, seed: int = 0, name: str = "en-dialog-synth", verbose: bool = True):
    clips = C.load_librispeech()
    return build_dialogs(clips, n, seed, name, "ensynth", lang="en", cond_weights=COND_WEIGHTS_EN, n_spk_p=(0.6, 0.4, 0.0),
                         min_dur_s=60.0, max_dur_s=240.0, verbose=verbose, min_clips=10)


def build_mixed(n: int = 5, seed: int = 0, name: str = "ru-en-mixed", verbose: bool = True):
    """Русский диалог с реальными английскими репликами (отдельные англоязычные дикторы) — проверка определения языка."""
    clips = C.load_voxforge() + C.load_ruls() + C.load_librispeech()

    def chooser(pools_all):
        ru = sorted(s for s in pools_all if lang_of_speaker(s) == "ru")
        en = sorted(s for s in pools_all if lang_of_speaker(s) == "en")

        def choose(rng, n_spk, target):
            k_en = 1 if n_spk <= 3 else 2
            out = [str(x) for x in rng.choice(ru, size=n_spk - k_en, replace=False)] + \
                  [str(x) for x in rng.choice(en, size=k_en, replace=False)]
            return out
        return choose

    return build_dialogs(clips, n, seed, name, "mix", lang="ru+en", cond_weights={
        "meeting": {"clean": 1, "noise": 1, "farfield": 1.5}, "call": {"clean": 1, "noise": 1, "phone": 1.5}},
        chooser=chooser, mixed=True, min_dur_s=60.0, max_dur_s=150.0, fixed_n_spk=[3, 3, 4, 3, 4],
        verbose=verbose, min_clips=10)
