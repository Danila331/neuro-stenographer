"""Plain data types shared by all stages (mirror of the Kotlin `:core` types, ARCHITECTURE §4)."""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class TimeSpan:
    start_ms: int
    end_ms: int

    @property
    def dur_ms(self) -> int:
        return self.end_ms - self.start_ms


@dataclass(frozen=True)
class Word:
    text: str
    start_ms: int
    end_ms: int
    confidence: float | None = None


@dataclass(frozen=True)
class SpeakerTurn:
    speaker: int  # 0-based raw cluster id from the diarizer
    start_ms: int
    end_ms: int


@dataclass
class Utterance:
    speaker: int  # 1-based, renumbered by first appearance (speaker_N)
    start_ms: int
    end_ms: int
    text: str
    words: list[Word] = field(default_factory=list)

    @property
    def speaker_id(self) -> str:
        return f"speaker_{self.speaker}"
