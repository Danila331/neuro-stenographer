"""ru-asr-dev: одиночные русские фразы из разных корпусов + синтетические искажения (noise/phone/reverb)."""
from __future__ import annotations

import json
from collections import defaultdict

import numpy as np

from . import audio as A
from . import corpora as C
from .common import SR, DatasetWriter, rng_for, stable_split
from .synth import load_noise_bank

NAME = "ru-asr-dev"

# квоты по источникам (сумма ≈ 600); масштабируются параметром n
QUOTA = {"fleurs": 0.24, "voxforge": 0.20, "golos_crowd": 0.20, "golos_farfield": 0.20, "ruls": 0.16}
SRC_COND = {"fleurs": ("clean", "read, studio-ish, FLEURS"), "voxforge": ("clean", "read, varied user mics"),
            "golos_crowd": ("clean", "read, crowd smartphone mics"),
            "golos_farfield": ("farfield", "voice assistant, far-field smart speaker"),
            "ruls": ("clean", "audiobook, LibriVox")}
DUR_RANGE = {"fleurs": (3.0, 20.0), "voxforge": (2.0, 14.0), "golos_crowd": (1.5, 14.0), "golos_farfield": (1.0, 14.0),
             "ruls": (3.0, 20.0)}
VARIANT_FRAC = 0.18  # доля фраз, для которых делаем искажённые варианты


def clip_dur(c: C.Clip) -> float | None:
    import soundfile as sf
    try:
        i = sf.info(str(c.path))
        return i.frames / i.samplerate
    except Exception:  # noqa: BLE001
        return None


def build(n: int = 600, seed: int = 0, name: str = NAME, verbose: bool = True):
    loaders = {"fleurs": C.load_fleurs, "voxforge": C.load_voxforge,
               "golos_crowd": lambda: [c for c in C.load_golos() if c.source == "golos_crowd"],
               "golos_farfield": lambda: [c for c in C.load_golos() if c.source == "golos_farfield"],
               "ruls": C.load_ruls}
    w = DatasetWriter(name, extra_cols=["ref_style", "speaker", "gender", "orig_id", "condition_detail", "license"])
    nb = None
    n_total = 0
    base_rows = []
    for src, frac in QUOTA.items():
        try:
            clips = loaders[src]()
        except FileNotFoundError:
            clips = []
        if not clips:
            print(f"[asr] source {src}: no data, skipped")
            continue
        rng = rng_for(seed, name, src)
        order = rng.permutation(len(clips))
        want = max(1, int(round(n * frac)))
        got, seen_spk = 0, defaultdict(int)
        lo, hi = DUR_RANGE[src]
        for i in order:
            c = clips[int(i)]
            # не более 3 фраз на одного диктора (разнообразие)
            if c.speaker and seen_spk[c.speaker] >= (3 if src == "voxforge" else 40):
                continue
            nwords = len(c.text.split())
            if nwords < 2 and src not in ("golos_crowd", "golos_farfield"):
                continue
            try:
                x = c.audio()
            except Exception:  # noqa: BLE001
                continue
            d = len(x) / SR
            if not (lo <= d <= hi):
                continue
            _, s0, e0 = A.trim_silence(x)
            if e0 - s0 < 0.3 * SR:
                continue
            cond, cdetail = SRC_COND[src]
            did = f"{c.id}"
            utt = [{"speaker_id": "speaker_1", "start_ms": int(s0 * 1000 / SR), "end_ms": int(e0 * 1000 / SR), "text": c.text}]
            if utt[0]["end_ms"] <= utt[0]["start_ms"]:
                continue
            w.add(did, x, utt, num_speakers=1, source=src, condition=cond, split=stable_split(did),
                  ref_style=c.ref_style, speaker=c.speaker or "", gender=c.gender or "", orig_id=c.id,
                  condition_detail=json.dumps({"note": cdetail}, ensure_ascii=False), license=C.LICENSES[src])
            base_rows.append((c, x, s0, e0))
            if c.speaker:
                seen_spk[c.speaker] += 1
            got += 1
            n_total += 1
            if got >= want:
                break
        if verbose:
            print(f"[asr] {src}: {got}/{want}")

    # синтетические искажённые варианты (тот же эталон; split наследуется от исходной фразы)
    rng = rng_for(seed, name, "variants")
    babble = [b[1] for b in base_rows[:20]]
    nb = load_noise_bank([a[:SR * 6] for a in babble])
    nvar = int(len(base_rows) * VARIANT_FRAC)
    pick = rng.choice(len(base_rows), size=min(nvar, len(base_rows)), replace=False)
    for k, bi in enumerate(pick):
        c, x, s0, e0 = base_rows[int(bi)]
        r = rng_for(seed, name, "var", int(bi))
        kind = ["noise", "phone", "reverb"][k % 3]
        detail: dict = {"variant_of": c.id}
        # дополняем тишиной по краям, чтобы реверберация/кодек не обрезались
        y = np.concatenate([np.zeros(SR // 2, np.float32), x, np.zeros(SR, np.float32)])
        off = SR // 2
        if kind == "noise":
            nk, nz = nb.pick(r, len(y))
            snr = float(r.uniform(5, 20))
            y = A.mix_at_snr(y, nz, snr)
            detail.update(noise=nk, snr_db=round(snr, 1))
        elif kind == "phone":
            codec = str(r.choice(list(A.PHONE_CODECS)))
            y = A.phone_channel(y, codec)
            detail.update(codec=codec)
        else:
            rl, rt60, dim, _ = A.make_rir(r, 1)
            y = A.convolve(y, rl[0])[: len(y) + SR]
            detail.update(rt60=round(rt60, 2))
        m = np.max(np.abs(y)) + 1e-9
        if m > 0.95:
            y = y * (0.95 / m)
        did = f"{c.id}__{kind}"
        utt = [{"speaker_id": "speaker_1", "start_ms": int((s0 + off) * 1000 / SR), "end_ms": int((e0 + off) * 1000 / SR),
                "text": c.text}]
        w.add(did, y, utt, num_speakers=1, source=c.source, condition=kind, split=stable_split(c.id),
              ref_style=c.ref_style, speaker=c.speaker or "", gender=c.gender or "", orig_id=c.id,
              condition_detail=json.dumps(detail, ensure_ascii=False), license=C.LICENSES[c.source])
    if verbose:
        print(f"[asr] base={len(base_rows)} variants={len(pick)}")
    return w.finalize()
