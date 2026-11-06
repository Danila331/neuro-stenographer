"""Pipeline orchestration, ARCHITECTURE §4:

  decode -> [denoise] -> VAD -> (ASR  ||  diarization) -> align -> punctuation -> export

ASR (with its VAD/chunking) and diarization run in two threads (sherpa-onnx releases the GIL);
each sherpa object gets `num_threads` from the config.
"""
from __future__ import annotations

import logging
import platform
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from . import __version__
from .align import AlignParams, align
from .asr import AsrEngine
from .audio import decode_audio, denoise, estimate_snr_db
from .config import config_hash
from .diar import Diarizer, SubprocessDiarizer
from .lid import LanguageId
from .punct import make_punctuator
from .types import SpeakerTurn, TimeSpan, Utterance, Word
from .vad import SileroVad, speech_chunks

log = logging.getLogger(__name__)
SR = 16000


@dataclass
class Result:
    utterances: list[Utterance]
    words: list[Word]
    turns: list[SpeakerTurn]
    speech: list[TimeSpan]
    chunks: list[TimeSpan]
    duration_ms: int
    stage_ms: dict[str, int]
    processing_ms: int
    info: dict = field(default_factory=dict)
    peak_rss_mb: float = 0.0


def tree_rss_mb() -> float:
    """RSS of this process plus its children (the diarization worker), in MB."""
    import psutil

    p = psutil.Process()
    total = p.memory_info().rss
    for c in p.children(recursive=True):
        try:
            total += c.memory_info().rss
        except psutil.Error:
            pass
    return total / 1e6


class RssSampler(threading.Thread):
    """Samples the process-tree RSS every 50 ms; `peak_mb` is the per-file peak (includes loaded models)."""

    def __init__(self):
        super().__init__(daemon=True)
        self.peak_mb = tree_rss_mb()
        self._halt = threading.Event()

    def run(self):
        while not self._halt.wait(0.05):
            self.peak_mb = max(self.peak_mb, tree_rss_mb())

    def finish(self) -> float:
        self._halt.set()
        self.join()
        self.peak_mb = max(self.peak_mb, tree_rss_mb())
        return self.peak_mb


def _ms(t0: float) -> int:
    return int(round((time.perf_counter() - t0) * 1000))


class Pipeline:
    def __init__(self, cfg: dict, models_root: Path):
        self.cfg, self.root = cfg, Path(models_root)
        self.threads = int(cfg["num_threads"])
        t0 = time.perf_counter()
        self.vad = SileroVad(cfg["vad"], self.root, 1)
        self.asr = AsrEngine(cfg["asr"], self.root, self.threads)
        self.second = None
        sp = cfg["asr"].get("second_pass")
        if sp:
            from .second_pass import SecondPass

            self.second = SecondPass(sp, cfg["asr"], self.root, self.threads)
        d = cfg["diarization"]
        dt = int(d.get("num_threads") or self.threads)
        self.diar = None
        if d.get("enabled", True):
            # in a subprocess: sherpa's diarization holds the GIL (see diar.py)
            self.diar = (SubprocessDiarizer if d.get("subprocess", True) else Diarizer)(d, self.root, dt)
        self.lid = None
        lang = cfg.get("language") or {}
        self.lang_mode = lang.get("mode", "auto")
        if self.lang_mode == "auto" and lang.get("lid_model") and cfg.get("asr_en"):
            self.lid = LanguageId(self.root / lang["lid_model"], self.threads, float(lang.get("lid_seconds", 10.0)))
        elif self.lang_mode == "auto":
            log.info("language.mode=auto without lid_model/asr_en: using ru")
        if self.lang_mode == "en" and not cfg.get("asr_en"):
            raise ValueError("language.mode=en requires an asr_en section in the config")
        self.asr_en = None
        self.punct, self.punct_source = make_punctuator(cfg, self.root)
        self.keep_punct = self.punct_source == "asr_native"
        self.load_ms = _ms(t0)
        self.rss_after_load_mb = tree_rss_mb()
        self.cfg_hash = config_hash(cfg)

    def models(self) -> dict[str, str]:
        c = self.cfg
        m = {"vad": c["vad"]["model"], "asr": c["asr"]["model_dir"],
             "diar_segmentation": c["diarization"]["segmentation"], "diar_embedding": c["diarization"]["embedding"]}
        if c["asr"].get("second_pass"):
            m["asr_second_pass"] = c["asr"]["second_pass"]["model_dir"]
        if c["audio"]["denoise"].get("enabled"):
            m["denoise"] = c["audio"]["denoise"]["model"]
        if self.punct_source == "llm":
            m["llm"] = c["llm"]["model"]
        if self.lid is not None:
            m["lid"] = c["language"]["lid_model"]
        if c.get("asr_en"):
            m["asr_en"] = c["asr_en"]["model_dir"]
        return m

    def process_file(self, path: str | Path, num_speakers: int | None = None) -> Result:
        t0 = time.perf_counter()
        x = decode_audio(path, SR)
        decode_ms = _ms(t0)
        return self.process_audio(x, num_speakers, decode_ms=decode_ms, t_start=t0)

    def process_audio(self, x: np.ndarray, num_speakers: int | None = None, decode_ms: int = 0,
                      t_start: float | None = None) -> Result:
        sampler = RssSampler()
        sampler.start()
        try:
            res = self._process_audio(x, num_speakers, decode_ms, t_start)
        finally:
            peak = sampler.finish()
        res.peak_rss_mb = round(peak, 1)
        return res

    def close(self) -> None:
        if self.diar is not None and hasattr(self.diar, "close"):
            self.diar.close()

    def _process_audio(self, x: np.ndarray, num_speakers: int | None, decode_ms: int,
                       t_start: float | None) -> Result:
        t_start = t_start if t_start is not None else time.perf_counter()
        cfg = self.cfg
        stage: dict[str, int] = {"decode": decode_ms}
        info: dict = {"punctuation_source": self.punct_source}
        dur_ms = len(x) * 1000 // SR
        if dur_ms == 0:
            return Result([], [], [], [], [], 0, stage, _ms(t_start), info)

        dn = cfg["audio"]["denoise"]
        t = time.perf_counter()
        if dn.get("enabled") and dn.get("model"):
            snr = estimate_snr_db(x)
            info["snr_db"] = None if snr == float("inf") else round(snr, 2)
            if snr < float(dn.get("snr_threshold_db", 12.0)):
                y = denoise(x, self.root / dn["model"], self.threads)
                x = np.pad(y, (0, max(0, len(x) - len(y))))[: len(x)].astype(np.float32)
                info["denoised"] = True
        stage["denoise"] = _ms(t)

        def run_asr():
            t1 = time.perf_counter()
            speech, chunks = speech_chunks(x, cfg, self.vad)
            vad_ms = _ms(t1)
            t_l = time.perf_counter()
            lang, raw = "ru", None
            if self.lang_mode == "en":
                lang = "en"
            elif self.lid is not None and speech:
                lang, raw = self.lid.detect(x, speech)
            lid_ms = _ms(t_l)
            info["language"] = lang
            info["language_detected"] = raw
            t2 = time.perf_counter()
            engine = self.asr
            if lang == "en":
                if self.asr_en is None:
                    self.asr_en = AsrEngine(cfg["asr_en"], self.root, self.threads)
                    info["asr_en_load_ms"] = _ms(t2)
                engine = self.asr_en
            words = engine.transcribe(x, chunks, keep_punct=self.keep_punct) if chunks else []
            asr_ms = _ms(t2)
            sp_ms = 0
            if self.second and words and lang == "ru":
                t3 = time.perf_counter()
                words, info["second_pass_chunks"] = self.second.apply(x, chunks, words, self.keep_punct)
                sp_ms = _ms(t3)
            return speech, chunks, words, vad_ms, asr_ms, sp_ms, lid_ms

        def run_diar():
            t1 = time.perf_counter()
            turns = self.diar.diarize(x, num_speakers) if self.diar else []
            return turns, _ms(t1)

        tw = time.perf_counter()
        with ThreadPoolExecutor(max_workers=2) as ex:
            fa = ex.submit(run_asr)
            fd = ex.submit(run_diar)
            speech, chunks, words, vad_ms, asr_ms, sp_ms, lid_ms = fa.result()
            turns, diar_ms = fd.result()
        stage.update(vad=vad_ms, lid=lid_ms, asr=asr_ms, diarization=diar_ms)
        if sp_ms:
            stage["asr_second_pass"] = sp_ms
        stage["asr_diar_wall"] = _ms(tw)

        t = time.perf_counter()
        utts = align(words, turns, AlignParams.from_config(cfg))
        stage["align"] = _ms(t)

        t = time.perf_counter()
        utts = self.punct.punctuate(utts)
        stage["punctuation"] = _ms(t)
        if hasattr(self.punct, "rejected"):
            info["llm_guard_rejected"] = [self.punct.rejected, self.punct.total]

        # clamp to the file duration (contract: 0 <= start < end <= duration)
        for u in utts:
            u.end_ms = min(u.end_ms, dur_ms)
            u.start_ms = min(u.start_ms, max(0, u.end_ms - 1))
        utts = [u for u in utts if u.text.strip() and u.end_ms > u.start_ms]
        processing = _ms(t_start)
        return Result(utts, words, turns, speech, chunks, dur_ms, stage, processing, info)

    def meta(self, res: Result) -> dict:
        import sherpa_onnx

        return {
            "processing_ms": res.processing_ms,
            "stage_ms": res.stage_ms,
            "model_load_ms": self.load_ms,
            "peak_rss_mb": res.peak_rss_mb,
            "rss_after_load_mb": round(self.rss_after_load_mb, 1),
            "audio_duration_ms": res.duration_ms,
            "rtf": round(res.processing_ms / res.duration_ms, 4) if res.duration_ms else None,
            "tier": self.cfg.get("tier"),
            "config_hash": self.cfg_hash,
            "device": f"{platform.system()} {platform.machine()} ({platform.processor() or 'cpu'})",
            "num_threads": self.threads,
            "models": self.models(),
            "platform": {"python": platform.python_version(), "os": platform.platform(),
                         "sherpa_onnx": sherpa_onnx.__version__, "refpipe": __version__},
            "info": res.info,
        }
