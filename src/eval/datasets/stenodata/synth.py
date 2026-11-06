"""Сборка диалога из реплик разных дикторов + условия (noise / reverb / phone / overlap).

Эталонные времена берутся из построения (позиция вставки), реплики предварительно обрезаны по
энергетическому VAD, так что start/end — границы речи.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

from . import audio as A
from .common import SR, rng_for

LEVEL_DBFS = -26.0  # активная речь каждой реплики до индивидуального усиления дикторов


@dataclass
class Seg:
    spk: str
    audio: np.ndarray       # float32 16 кГц, обрезана по краям, нормализована
    text: str
    kind: str = "turn"      # turn | backchannel
    src: str = ""
    cid: str = ""

    @property
    def dur_ms(self) -> int:
        return len(self.audio) * 1000 // SR


def prepare_seg(x: np.ndarray, spk: str, text: str, src: str = "", cid: str = "", kind: str = "turn",
                speed: float = 1.0, min_ms: int = 250) -> Seg | None:
    y, _, _ = A.trim_silence(x)
    if len(y) < int(min_ms * SR / 1000):
        return None
    if abs(speed - 1.0) > 1e-3:
        y = A.time_stretch(y, speed)  # быстрый темп (call), тон сохраняется
        y, _, _ = A.trim_silence(y)
    y = A.fade(A.set_active_dbfs(y, LEVEL_DBFS))
    return Seg(spk=spk, audio=y, text=text, kind=kind, src=src, cid=cid)


@dataclass
class DialogPlan:
    order: list[Seg]
    gaps_ms: list[int]            # gaps[i] — между order[i-1] и order[i]; gaps[0] = 0 (смещение первой реплики)
    lead_ms: int = 600
    tail_ms: int = 700


GAP_PROFILES = {
    # p_overlap — доля смен спикера с перекрытием; overlap_ms — диапазон глубины перекрытия;
    # gaps — смесь (вес, диапазон мс) для обычных пауз при смене спикера
    "normal": dict(p_overlap=0.12, overlap_ms=(120, 400), same_speaker_gap=(150, 700),
                   gaps=[(0.40, (100, 350)), (0.40, (350, 700)), (0.20, (700, 901))]),
    "overlap": dict(p_overlap=0.55, overlap_ms=(500, 3000), same_speaker_gap=(100, 500),
                    gaps=[(0.40, (100, 350)), (0.40, (350, 700)), (0.20, (700, 901))]),
    # быстрые звонки: короткие паузы, больше перебиваний (~25 %)
    "call": dict(p_overlap=0.25, overlap_ms=(100, 600), same_speaker_gap=(60, 350),
                 gaps=[(0.65, (30, 300)), (0.35, (300, 600))]),
    "call_overlap": dict(p_overlap=0.50, overlap_ms=(200, 1800), same_speaker_gap=(60, 300),
                         gaps=[(0.65, (30, 300)), (0.35, (300, 600))]),
}


def profile_for(scenario: str, condition: str) -> str:
    if scenario == "call":
        return "call_overlap" if condition == "overlap" else "call"
    return "overlap" if condition == "overlap" else "normal"


def sample_gap(rng: np.random.Generator, same_speaker: bool, prof: dict, prev_ms: int, cur_ms: int) -> int:
    if same_speaker:
        return int(rng.integers(*prof["same_speaker_gap"]))
    if rng.random() < prof["p_overlap"]:
        d = int(rng.integers(*prof["overlap_ms"]))
        d = min(d, int(0.6 * min(prev_ms, cur_ms)))
        return -max(d, 60)
    u, acc = rng.random(), 0.0
    for w, (lo, hi) in prof["gaps"]:
        acc += w
        if u < acc:
            return int(rng.integers(lo, hi))
    return int(rng.integers(*prof["gaps"][-1][1]))


def plan_dialog(rng: np.random.Generator, pools: dict[str, list[Seg]], target_s: float, profile: str = "normal",
                min_s: float = 60.0, p_same: float = 0.18) -> DialogPlan | None:
    """pools: speaker -> сегменты (копии списков; из них извлекаем без возвращения)."""
    prof = GAP_PROFILES[profile]
    pools = {k: list(v) for k, v in pools.items()}
    for v in pools.values():
        rng.shuffle(v)
    spks = list(pools)
    order: list[Seg] = []
    gaps: list[int] = []
    total = 0.0
    last = None
    while total < target_s:
        avail = [s for s in spks if pools[s]]
        if not avail:
            break
        cand = [s for s in avail if s != last] or avail
        if last in avail and rng.random() < p_same:
            s = last
        else:
            # предпочитаем тех, у кого осталось больше реплик (равномернее расходуем)
            w = np.array([len(pools[c]) for c in cand], dtype=float)
            s = cand[int(rng.choice(len(cand), p=w / w.sum()))]
        seg = pools[s].pop()
        g = 0 if not order else sample_gap(rng, s == last, prof, order[-1].dur_ms, seg.dur_ms)
        order.append(seg)
        gaps.append(g)
        total += seg.dur_ms / 1000 + max(g, 0) / 1000
        last = s
    if total < min_s or len({o.spk for o in order}) < min(2, len(spks)):
        return None
    return DialogPlan(order=order, gaps_ms=gaps)


def render(plan: DialogPlan, rng: np.random.Generator, speaker_gain_db: dict[str, float] | None = None,
           rirs: dict[str, np.ndarray] | None = None, room_noise_db: float = -50.0):
    """Собирает временную шкалу. Возвращает (audio float32, utts[dict speaker_id,start_ms,end_ms,text])."""
    speaker_gain_db = speaker_gain_db or {}
    starts: list[int] = []
    cur_end = int(plan.lead_ms * SR / 1000)
    for i, seg in enumerate(plan.order):
        if i == 0:
            st = cur_end
        else:
            st = cur_end + int(plan.gaps_ms[i] * SR / 1000)
            st = max(st, starts[-1] + int(0.25 * SR))
        starts.append(st)
        cur_end = st + len(seg.audio)
    tail = int(plan.tail_ms * SR / 1000) + (int(1.0 * SR) if rirs else 0)
    total = max(s + len(g.audio) for s, g in zip(starts, plan.order)) + tail
    mix = np.zeros(total + SR, np.float32)
    utts = []
    for st, seg in zip(starts, plan.order):
        x = seg.audio * np.float32(10 ** (speaker_gain_db.get(seg.spk, 0.0) / 20))
        if rirs is not None:
            x = A.convolve(x, rirs[seg.spk])
        mix[st: st + len(x)] += x[: len(mix) - st]
        utts.append({"speaker_id": seg.spk, "start_ms": int(round(st * 1000 / SR)),
                     "end_ms": int(round((st + len(seg.audio)) * 1000 / SR)), "text": seg.text})
    mix = mix[:total]
    # общий комнатный шум-пол: маскирует различия «фона» между исходными записями
    if room_noise_db is not None:
        pn = A.pink_noise(len(mix), rng)
        mix = mix + pn * np.float32(10 ** (room_noise_db / 20))
    return mix, utts


def finalize_level(x: np.ndarray, peak: float = 0.9) -> np.ndarray:
    m = float(np.max(np.abs(x)) + 1e-9)
    if m > peak:
        x = x * (peak / m)
    return x.astype(np.float32)


def apply_condition(mix: np.ndarray, utts_spk: list[str], condition: str, rng: np.random.Generator, noise_bank,
                    detail: dict) -> np.ndarray:
    """noise / phone / (clean|overlap — без изменений). reverb применяется при render (rirs)."""
    if condition in ("noise", "noise+phone"):
        kind, nz = noise_bank.pick(rng, len(mix))
        snr = float(rng.uniform(5, 20))
        mix = A.mix_at_snr(mix, nz, snr)
        detail["noise"] = kind
        detail["snr_db"] = round(snr, 1)
    if condition in ("phone", "noise+phone"):
        codec = str(rng.choice(list(A.PHONE_CODECS)))
        mix = A.phone_channel(mix, codec)
        detail["codec"] = codec
    return mix


@dataclass
class NoiseBank:
    """Источники шума: MUSAN music (fma), ESC-50 (в т.ч. кошки), babble из речи, розовый шум."""
    music: list = field(default_factory=list)       # пути wav
    esc: list = field(default_factory=list)         # (путь, класс)
    babble_pool: list = field(default_factory=list)  # np arrays речи для babble

    def pick(self, rng: np.random.Generator, n: int):
        import soundfile as sf
        kinds = []
        if self.music:
            kinds.append("music")
        if self.esc:
            kinds += ["esc", "esc"]
        if self.babble_pool:
            kinds.append("babble")
        kinds.append("pink")
        k = str(rng.choice(kinds))
        if k == "music":
            p = self.music[int(rng.integers(len(self.music)))]
            info = sf.info(p)
            need = int(n * info.samplerate / SR)
            start = int(rng.integers(0, max(1, info.frames - need)))
            x, sr = sf.read(p, start=start, frames=min(need, info.frames), dtype="float32", always_2d=True)
            x = x.mean(1)
            if sr != SR:
                import soxr
                x = soxr.resample(x, sr, SR, quality="HQ")
            return "music", A.random_crop(x, n, rng)
        if k == "esc":
            parts, cls = [], None
            tot = 0
            while tot < n:
                p, c = self.esc[int(rng.integers(len(self.esc)))]
                x, sr = sf.read(p, dtype="float32")
                if x.ndim > 1:
                    x = x.mean(1)
                if sr != SR:
                    import soxr
                    x = soxr.resample(x, sr, SR, quality="HQ")
                parts.append(x)
                tot += len(x)
                cls = c if cls is None else "mixed"
            return f"esc:{cls}", np.concatenate(parts)[:n]
        if k == "babble":
            m = np.zeros(n, np.float32)
            for _ in range(int(rng.integers(4, 8))):
                x = self.babble_pool[int(rng.integers(len(self.babble_pool)))]
                x = np.tile(x, int(np.ceil(n / len(x)) + 1))
                s = int(rng.integers(0, len(x) - n))
                m += x[s: s + n]
            return "babble", m
        return "pink", A.pink_noise(n, rng)


def load_noise_bank(babble_segs: list[np.ndarray] | None = None) -> NoiseBank:
    from .common import raw_root
    import csv
    nb = NoiseBank()
    mus = raw_root() / "musan"
    if mus.exists():
        nb.music = sorted(str(p) for p in mus.rglob("music-*.wav"))
    esc = raw_root() / "esc50" / "ESC-50"
    meta = esc / "meta" / "esc50.csv"
    if meta.exists():
        with open(meta, encoding="utf-8") as f:
            for r in csv.DictReader(f):
                # исключаем классы, содержащие речь/смех, чтобы шум не добавлял «чужих говорящих»
                if r["category"] in ("crying_baby", "sneezing", "laughing", "coughing", "breathing", "snoring"):
                    continue
                p = esc / "audio" / r["filename"]
                if p.exists():
                    nb.esc.append((str(p), r["category"]))
    nb.babble_pool = babble_segs or []
    return nb
