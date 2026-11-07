"""Stage ASR: chunks -> words with ABSOLUTE time (sherpa-onnx OfflineRecognizer).

Engines (`asr.engine`):
  gigaam_rnnt   GigaAM v2/v3 transducer (NeMo layout: encoder.int8.onnx, decoder.onnx, joiner.onnx);
                the v3 *punct* model emits punctuation and capitals inside the tokens
  nemo_tdt      NeMo TDT transducer (Parakeet; token durations are used for word ends), alias nemo_transducer
  gigaam_ctc    GigaAM v2/v3 CTC (model.int8.onnx), alias nemo_ctc
  transducer    any other sherpa transducer (zipformer ...): bpe/char tokens
  whisper       Whisper encoder/decoder (no token timestamps: words are spread over the chunk)

Word timing (documented because the Kotlin port must do the same):
  * sherpa returns one START timestamp (seconds, 40 ms frame grid for GigaAM) per token and no
    durations for these models.  GigaAM tokens are single characters + ' ' (word boundary).
  * word.start = timestamp of its first non-blank token (+ word_start_offset_ms);
    word.end   = timestamp of its last token + word_tail_ms (default 40 ms = one encoder frame; for
                 TDT models such as Parakeet the real token duration is used instead),
                 clipped to the next word's start (so words never overlap) and to the chunk end.
  * absolute time = chunk.start_ms + round(ts * 1000).
  * punctuation tokens (punct models) are glued to the word text but ignored for its start/end/confidence;
  * word confidence = mean exp(log p) of its tokens (None if the model gives no log-probs).
  * Whisper (no token timestamps): `enable_segment_timestamps` gives text segments with start/duration;
    words are placed proportionally to their character count inside their segment (coarse, ~0.3 s
    error); without segments, over the whole chunk.
"""
from __future__ import annotations

import math
from pathlib import Path

import numpy as np

from .types import TimeSpan, Word

SR = 16000
PUNCT_CHARS = set(".,!?;:…—–-«»\"'()[]")


def _pick(d: Path, *names: str) -> str:
    for n in names:
        p = d / n
        if p.is_file():
            return str(p)
    cands = sorted(d.glob(names[0].replace(".int8", "*").replace(".onnx", "*.onnx")))
    if cands:
        return str(cands[0])
    raise FileNotFoundError(f"none of {names} in {d}")


def plain_word(t: str) -> str:
    """`none` punctuation mode: lower-case, only letters/digits/inner hyphen-apostrophe."""
    t = "".join(c for c in t if c.isalnum() or c in "-'").strip("-'")
    return t.lower()


def build_words(tokens: list[str], ts: list[float], log_probs: list[float] | None, chunk: TimeSpan,
                tail_ms: int = 40, start_offset_ms: int = 0, keep_punct: bool = False,
                durations: list[float] | None = None) -> list[Word]:
    """Pure function: tokens (characters or BPE pieces, ' '/'▁' = word boundary) -> absolute words."""
    n = min(len(tokens), len(ts))
    words: list[dict] = []  # text, start, last, probs
    cur: dict | None = None

    def flush():
        nonlocal cur
        if cur and cur["text"].strip():
            words.append(cur)
        cur = None

    for i in range(n):
        t = tokens[i].replace("▁", " ")
        if t.startswith("<") and t.endswith(">") and len(t) > 2:  # special tokens
            continue
        lead = t[:1].isspace()
        body = t.strip()
        if lead:
            flush()
        if body:
            t_ms = chunk.start_ms + int(ts[i] * 1000 + 0.5) + start_offset_ms
            if cur is None:
                cur = {"text": "", "start": None, "last": None, "probs": [], "last_dur": None}
            cur["text"] += body
            if not all(c in PUNCT_CHARS for c in body):  # punctuation never defines word boundaries/times
                if cur["start"] is None:
                    cur["start"] = t_ms
                cur["last"] = t_ms
                cur["last_dur"] = int(durations[i] * 1000 + 0.5) if durations is not None and i < len(durations) else None
                if log_probs is not None and i < len(log_probs):
                    cur["probs"].append(math.exp(log_probs[i]))
            elif cur["start"] is None:
                cur["punct_only_ts"] = t_ms
        if t[-1:].isspace() and len(t) > 1:
            flush()
    flush()

    # words made of punctuation only have no times: glue them to the previous word (native mode) or drop
    merged: list[dict] = []
    for w in words:
        if w["start"] is None:
            if keep_punct and merged:
                merged[-1]["text"] += w["text"]
            continue
        merged.append(w)
    words = merged
    out: list[Word] = []
    for k, w in enumerate(words):
        text = w["text"] if keep_punct else plain_word(w["text"])
        if not text:
            continue
        start = max(chunk.start_ms, w["start"])
        end = w["last"] + (w["last_dur"] if w.get("last_dur") else tail_ms)  # TDT models give real durations
        if k + 1 < len(words):
            end = min(end, max(words[k + 1]["start"], start + 1))
        end = min(max(end, start + 1), max(chunk.end_ms, start + 1))
        conf = float(np.mean(w["probs"])) if w["probs"] else None
        out.append(Word(text, start, end, conf))
    return out


def spread_words(text: str, chunk: TimeSpan, keep_punct: bool = False) -> list[Word]:
    """No token timestamps: distribute words over the chunk proportionally to their length."""
    ws = [w if keep_punct else plain_word(w) for w in text.split()]
    ws = [w for w in ws if w]
    if not ws:
        return []
    total = sum(len(w) + 1 for w in ws)
    out, acc = [], 0
    for w in ws:
        a = chunk.start_ms + chunk.dur_ms * acc // total
        acc += len(w) + 1
        b = chunk.start_ms + chunk.dur_ms * acc // total
        out.append(Word(w, a, max(b - 1, a + 1)))
    return out


class AsrEngine:
    def __init__(self, asr_cfg: dict, models_root: Path, num_threads: int):
        import sherpa_onnx as so

        self.cfg = asr_cfg
        self.engine = asr_cfg["engine"]
        d = models_root / asr_cfg["model_dir"]
        if not d.is_dir():
            raise FileNotFoundError(f"ASR model dir not found: {d} (run scripts/fetch_models.py)")
        tokens = str(d / "tokens.txt")
        dec = asr_cfg.get("decoding", "greedy_search")
        hot = asr_cfg.get("hotwords_file") or ""
        if hot:
            hot = str(models_root / hot)
        if self.engine in ("gigaam_ctc", "nemo_ctc"):
            self.rec = so.OfflineRecognizer.from_nemo_ctc(
                model=_pick(d, "model.int8.onnx", "model.onnx"), tokens=tokens, num_threads=num_threads,
                decoding_method="greedy_search")
        elif self.engine in ("gigaam_rnnt", "nemo_tdt", "nemo_transducer", "transducer"):
            nemo = self.engine != "transducer"
            self.rec = so.OfflineRecognizer.from_transducer(
                encoder=_pick(d, "encoder.int8.onnx", "encoder.onnx"),
                decoder=_pick(d, "decoder.int8.onnx", "decoder.onnx"),
                joiner=_pick(d, "joiner.int8.onnx", "joiner.onnx"),
                tokens=tokens, num_threads=num_threads, decoding_method=dec,
                max_active_paths=int(asr_cfg.get("max_active_paths", 4)),
                hotwords_file=hot, hotwords_score=float(asr_cfg.get("hotwords_score", 1.5)),
                model_type="nemo_transducer" if nemo else "transducer")  # feature dim comes from the model
        elif self.engine == "whisper":
            self.rec = so.OfflineRecognizer.from_whisper(
                encoder=_pick(d, "encoder.int8.onnx", "turbo-encoder.int8.onnx", "encoder.onnx"),
                decoder=_pick(d, "decoder.int8.onnx", "turbo-decoder.int8.onnx", "decoder.onnx"),
                tokens=_pick(d, "tokens.txt", "turbo-tokens.txt"),
                language=asr_cfg.get("language", "ru"), task="transcribe", num_threads=num_threads,
                enable_segment_timestamps=True)
        else:
            raise ValueError(f"unknown asr.engine {self.engine!r}")
        self.has_ts = self.engine != "whisper"

    def recognize(self, x: np.ndarray, chunks: list[TimeSpan], chunk_gain: dict | None = None):
        """Returns list of (chunk, sherpa result) in chunk order."""
        min_n = int(float(self.cfg["chunk"].get("min_s", 0)) * SR)
        bs = max(1, int(self.cfg.get("batch_size", 1)))
        results = []
        for i in range(0, len(chunks), bs):
            batch = chunks[i:i + bs]
            streams = []
            for c in batch:
                seg = x[c.start_ms * SR // 1000:c.end_ms * SR // 1000]
                if chunk_gain:  # normalize_gain.scope = asr_chunk: peak-normalise this chunk alone
                    from .audio import normalize_gain

                    seg = normalize_gain(seg, {**chunk_gain, "apply_if_peak_below_dbfs": chunk_gain.get("apply_if_peak_below_dbfs")})[0]
                if len(seg) < min_n:  # pad with silence up to chunk.min_s
                    seg = np.concatenate([seg, np.zeros(min_n - len(seg), dtype=np.float32)])
                s = self.rec.create_stream()
                s.accept_waveform(SR, seg)
                streams.append(s)
            self.rec.decode_streams(streams)
            results.extend(zip(batch, (s.result for s in streams)))
        return results

    def transcribe(self, x: np.ndarray, chunks: list[TimeSpan], keep_punct: bool = False,
                   drop_tiny_chars: int = 0, chunk_gain: dict | None = None) -> list[Word]:
        """drop_tiny_chars (postprocess.drop_tiny_chunks.max_chars): chunks whose recognised text has at most
        that many letters/digits are discarded (hallucinated "а/э/няу" on noise or meowing)."""
        tail = int(self.cfg.get("word_tail_ms", 40))
        off = int(self.cfg.get("word_start_offset_ms", 0))
        words: list[Word] = []
        for chunk, r in self.recognize(x, chunks, chunk_gain):
            if drop_tiny_chars and sum(c.isalnum() for c in r.text) <= drop_tiny_chars:
                continue
            if self.has_ts and len(r.timestamps) >= len(r.tokens) > 0:
                lp = list(r.ys_log_probs) if len(r.ys_log_probs) == len(r.tokens) else None
                du = list(r.durations) if len(r.durations) == len(r.tokens) else None
                words.extend(build_words(list(r.tokens), list(r.timestamps), lp, chunk, tail, off, keep_punct, du))
            elif self.engine == "whisper" and len(r.segment_texts) and len(r.segment_texts) == len(r.segment_timestamps):
                for txt, st, du in zip(r.segment_texts, r.segment_timestamps, r.segment_durations):
                    a = chunk.start_ms + int(st * 1000 + 0.5)
                    b = min(chunk.end_ms, a + max(1, int(du * 1000 + 0.5)))
                    words.extend(spread_words(txt, TimeSpan(a, max(b, a + 1)), keep_punct))
            else:
                words.extend(spread_words(r.text, chunk, keep_punct))
        return words
