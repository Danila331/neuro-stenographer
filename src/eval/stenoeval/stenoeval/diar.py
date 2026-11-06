"""DER / JER (pyannote.metrics) and speaker-count accuracy.

Reference and hypothesis annotations are built from *utterance-level* segments
``[start_ms/1000, end_ms/1000]`` labelled with ``speaker_id``. Segments of the same speaker that
overlap are merged; segments of different speakers may overlap (overlapped speech is scored,
``skip_overlap=False``). The evaluation map (UEM) is ``[0, max(duration, last ref/hyp end)]`` so
false alarms outside the reference speech are counted.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

from pyannote.core import Annotation, Segment, Timeline
from pyannote.metrics.diarization import DiarizationErrorRate, JaccardErrorRate

from .transcript import Utt

# pyannote.metrics `collar` = TOTAL width removed around each reference boundary (+-collar/2).
# NIST / DIHARD "0.25 s collar" (+-0.25 s) is therefore pyannote collar=0.5.
COLLARS = {"c0": 0.0, "c025": 0.25, "nist": 0.5}


def to_annotation(utts: Sequence[Utt]) -> Annotation:
    ann = Annotation()
    for u in utts:
        if u.end_ms > u.start_ms and u.start_ms >= 0:
            ann[Segment(u.start_ms / 1000.0, u.end_ms / 1000.0), len(ann)] = u.speaker_id
    return ann.support() if len(ann) else ann


def _speech_time(ann: Annotation) -> float:
    return sum(s.duration for s in ann.get_timeline().support())


@dataclass(frozen=True)
class DerResult:
    total: float          # reference speech time (s), overlap counted per speaker
    missed: float
    false_alarm: float
    confusion: float
    der: float | None     # None = undefined (empty reference, non-empty hypothesis)

    @property
    def errors(self) -> float:
        return self.missed + self.false_alarm + self.confusion


def _uem(ref: Annotation, hyp: Annotation, duration_ms: int | None) -> Timeline:
    end = max(
        (duration_ms or 0) / 1000.0,
        max((s.end for s in ref.itersegments()), default=0.0),
        max((s.end for s in hyp.itersegments()), default=0.0),
    )
    return Timeline([Segment(0.0, end)]) if end > 0 else Timeline()


def der(ref: Sequence[Utt], hyp: Sequence[Utt], collar: float = 0.0, duration_ms: int | None = None) -> DerResult:
    r, h = to_annotation(ref), to_annotation(hyp)
    if not len(r) and not len(h):
        return DerResult(0.0, 0.0, 0.0, 0.0, 0.0)
    metric = DiarizationErrorRate(collar=collar, skip_overlap=False)
    d = metric(r, h, uem=_uem(r, h, duration_ms), detailed=True)
    total = float(d["total"])
    comp = (float(d["missed detection"]), float(d["false alarm"]), float(d["confusion"]))
    value = sum(comp) / total if total > 0 else (0.0 if sum(comp) == 0 else None)
    return DerResult(total, comp[0], comp[1], comp[2], value)


def jer(ref: Sequence[Utt], hyp: Sequence[Utt], collar: float = 0.0, duration_ms: int | None = None) -> float | None:
    """Jaccard error rate (pyannote.metrics); None when undefined (empty reference/hypothesis)."""
    r, h = to_annotation(ref), to_annotation(hyp)
    if not len(r) and not len(h):
        return 0.0
    if not len(r):
        return None
    if not len(h):
        return 1.0
    metric = JaccardErrorRate(collar=collar, skip_overlap=False)
    d = metric(r, h, uem=_uem(r, h, duration_ms), detailed=True)
    return float(d["jaccard error rate"])


def num_speakers(utts: Sequence[Utt]) -> int:
    return len({u.speaker_id for u in utts})
