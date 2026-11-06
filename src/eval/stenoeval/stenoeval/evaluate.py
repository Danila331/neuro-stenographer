"""All per-file metrics for one (reference, hypothesis) pair."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Sequence

from . import diar, wer as werm
from .textnorm import MODES
from .timing import HypWord, TimingResult, timing
from .transcript import Utt


@dataclass
class PairMetrics:
    duration_ms: int | None
    wer: dict[str, werm.WerCounts] = field(default_factory=dict)      # by normalisation mode
    cpwer: dict[str, werm.CpWer] = field(default_factory=dict)        # by normalisation mode
    der: dict[str, diar.DerResult] = field(default_factory=dict)      # by collar key (diar.COLLARS)
    jer: float | None = None
    jer_nist: float | None = None
    spk_ref: int = 0
    spk_hyp: int = 0
    timing: TimingResult | None = None

    @property
    def spk_correct(self) -> bool:
        return self.spk_ref == self.spk_hyp


def evaluate_pair(
    ref: Sequence[Utt],
    hyp: Sequence[Utt],
    duration_ms: int | None = None,
    hyp_words: Sequence[HypWord] | None = None,
) -> PairMetrics:
    pm = PairMetrics(duration_ms=duration_ms)
    for mode in MODES:
        pm.wer[mode] = werm.wer(ref, hyp, mode)
    for mode in ("raw", "norm", "norm_num"):
        pm.cpwer[mode] = werm.cpwer(ref, hyp, mode)
    for key, collar in diar.COLLARS.items():
        pm.der[key] = diar.der(ref, hyp, collar, duration_ms)
    pm.jer = diar.jer(ref, hyp, 0.0, duration_ms)
    pm.jer_nist = diar.jer(ref, hyp, diar.COLLARS["nist"], duration_ms)
    pm.spk_ref = diar.num_speakers(ref)
    pm.spk_hyp = diar.num_speakers(hyp)
    pm.timing = timing(ref, hyp, hyp_words)
    return pm
