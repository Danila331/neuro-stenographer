"""Punctuator interface + guard of unchanged words (docs/team/ARCHITECTURE_TEAM.md §6).

Modes (`punctuation.source`):
  none        text stays the plain space-joined words (lower-case, no punctuation);
  asr_native  the ASR model itself emits punctuation/case (GigaAM v3 *punct* models); words keep it;
  llm         llama.cpp rewrites each utterance; guard() rejects any output that changes the words
              (implementation hook: `LlmPunctuator`, prompts are owned by llm-bench);
  punct_model reserved (separate punctuation model), not implemented in the reference.

`norm` / `guard_text` are the reference for the Kotlin port (cases: tests/guard_cases.json).
"""
from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from pathlib import Path

from .types import Utterance

log = logging.getLogger(__name__)

TERMINAL = ".!?…"


def norm(s: str) -> str:
    """lower -> yo->e -> drop everything except letters/digits/whitespace -> collapse spaces."""
    s = s.lower().replace("ё", "е")
    out = []
    for c in s:
        if c.isalnum():
            out.append(c)
        elif c.isspace():
            out.append(" ")
    return " ".join("".join(out).split())


def fallback_text(inp: str) -> str:
    """Input text + capital first letter + final period (used when the guard rejects)."""
    t = inp.strip()
    if not t:
        return t
    t = t[0].upper() + t[1:]
    if t[-1] not in TERMINAL:
        t += "."
    return t


def guard_text(inp: str, out: str) -> tuple[str, bool]:
    """Returns (text, accepted). Words are never lost or added: otherwise the input is kept."""
    out = out.strip()
    if out and norm(out).split() == norm(inp).split():
        return out, True
    return fallback_text(inp), False


class Punctuator(ABC):
    @abstractmethod
    def punctuate(self, utterances: list[Utterance]) -> list[Utterance]: ...

    def close(self) -> None:  # pragma: no cover
        pass


class NonePunctuator(Punctuator):
    def punctuate(self, utterances):
        return utterances


class AsrNativePunctuator(Punctuator):
    """Words already carry punctuation/case from the ASR model; only the guard is applied
    (trivially true, but keeps the invariant `text == join(words)` explicit)."""

    def punctuate(self, utterances):
        return utterances


class LlmUnavailable(RuntimeError):
    pass


LLM_SYSTEM = (
    "Ты расставляешь знаки препинания и заглавные буквы в расшифровке русской речи. "
    "Не меняй, не добавляй и не удаляй слова и не меняй их порядок. Верни только исправленный текст."
)


class LlmPunctuator(Punctuator):
    """llama.cpp via llama-cpp-python (`pip install llama-cpp-python`, optional extra `llm`).

    Minimal reference implementation; llm-bench owns the real prompts/long-text strategy.  Every
    output goes through `guard_text`, so a bad LLM can only cost quality, never words.
    """

    def __init__(self, model_path: Path, n_ctx: int = 4096, n_threads: int = 4, gpu_layers: int = 0,
                 max_words: int = 80):
        try:
            from llama_cpp import Llama  # type: ignore
        except Exception as e:  # ImportError or build problems
            raise LlmUnavailable(f"llama-cpp-python is not available: {e}") from e
        if not Path(model_path).is_file():
            raise LlmUnavailable(f"LLM model not found: {model_path}")
        self.llm = Llama(model_path=str(model_path), n_ctx=n_ctx, n_threads=n_threads,
                         n_gpu_layers=gpu_layers, verbose=False)
        self.max_words = max_words
        self.rejected = 0
        self.total = 0

    def _one(self, text: str) -> str:
        r = self.llm.create_chat_completion(
            messages=[{"role": "system", "content": LLM_SYSTEM},
                      {"role": "user", "content": "/no_think\n" + text}],
            temperature=0.0, max_tokens=max(64, int(len(text) * 1.2)),
        )
        out = r["choices"][0]["message"]["content"]
        if "</think>" in out:
            out = out.split("</think>")[-1]
        return out.strip()

    def punctuate(self, utterances):
        for u in utterances:
            words = u.text.split()
            parts = [" ".join(words[i:i + self.max_words]) for i in range(0, len(words), self.max_words)]
            res = []
            for part in parts:
                self.total += 1
                try:
                    cand = self._one(part)
                except Exception as e:  # keep the pipeline alive
                    log.warning("LLM failed: %s", e)
                    cand = ""
                txt, ok = guard_text(part, cand)
                self.rejected += 0 if ok else 1
                res.append(txt)
            u.text = " ".join(res)
        return utterances


def make_punctuator(cfg: dict, models_root: Path) -> tuple[Punctuator, str]:
    """Returns (punctuator, effective_source). Falls back to `none` if the LLM is unavailable."""
    src = (cfg.get("punctuation") or {}).get("source", "none")
    if src == "asr_native":
        return AsrNativePunctuator(), src
    if src == "llm":
        llm = cfg.get("llm") or {}
        try:
            return LlmPunctuator(models_root / llm["model"], llm.get("n_ctx", 4096),
                                 llm.get("n_threads", 4), llm.get("gpu_layers", 0)), src
        except (LlmUnavailable, KeyError, TypeError) as e:
            log.warning("punctuation.source=llm unavailable (%s); falling back to none", e)
            return NonePunctuator(), "none(fallback from llm)"
    if src not in ("none", "punct_model"):
        log.warning("unknown punctuation.source=%r; using none", src)
    return NonePunctuator(), "none"
