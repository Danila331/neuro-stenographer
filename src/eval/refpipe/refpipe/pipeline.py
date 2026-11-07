"""Pipeline orchestration, ARCHITECTURE §4:

  decode -> [denoise] -> VAD -> (ASR  ||  diarization) -> align -> punctuation -> export

ASR (with its VAD/chunking) and diarization run in two threads (sherpa-onnx releases the GIL);
each sherpa object gets `num_threads` from the config.
"""
from __future__ import annotations

import hashlib
import json
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
from .audio import agc, decode_audio, denoise, estimate_snr_db, normalize_gain, segment_gain
from .config import DEFAULTS, canonical, config_hash, deep_merge
from .diar import Diarizer, SubprocessDiarizer
from .lid import LanguageId, smooth_languages
from .punct import make_punctuator
from .relabel import Params as RelabelParams, UnitEmbedder, embed_units, label_units, make_units, stock_is_single
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
    def __init__(self, cfg: dict, models_root: Path, cache_dir: Path | None = None):
        self.cfg, self.root = cfg, Path(models_root)
        self.cache_dir = Path(cache_dir) if cache_dir else None
        if self.cache_dir:
            self.cache_dir.mkdir(parents=True, exist_ok=True)
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
        self.relabel = (d.get("relabel") or {}) if (d.get("relabel") or {}).get("enabled") else None
        self.embedder = UnitEmbedder(self.root / d["embedding"], dt) if self.relabel else None
        self.lid = None
        lang = cfg.get("language") or {}
        self.lang_mode = lang.get("mode", "auto")
        if self.lang_mode == "auto" and lang.get("lid_model") and cfg.get("asr_en"):
            self.lid = LanguageId(self.root / lang["lid_model"], 1, float(lang.get("lid_seconds", 10.0)))
        elif self.lang_mode == "auto":
            log.info("language.mode=auto without lid_model/asr_en: using ru")
        if self.lang_mode == "en" and not cfg.get("asr_en"):
            raise ValueError("language.mode=en requires an asr_en section in the config")
        self.per_chunk = bool(lang.get("per_chunk")) and self.lid is not None
        self.switch_min_ms = int(lang.get("switch_min_ms", 6000))
        self.asr_en = None
        dt_ = (cfg.get("postprocess") or {}).get("drop_tiny_chunks") or {}
        self.drop_tiny = int(dt_.get("max_chars", 2)) if dt_.get("enabled") else 0
        self.punct, self.punct_source = make_punctuator(cfg, self.root)
        self.keep_punct = self.punct_source == "asr_native"
        self.load_ms = _ms(t0)
        self.rss_after_load_mb = tree_rss_mb()
        self.cfg_hash = config_hash(cfg)

    # -- optional stage cache (--cache): ASR and diarization outputs keyed by audio bytes + the config
    #    that influences them, so align/punctuation/threshold sweeps do not recompute the models.
    def _ckey(self, stage: str, audio_key: str | None, parts: dict) -> Path | None:
        if not (self.cache_dir and audio_key):
            return None
        h = hashlib.sha256((audio_key + stage + canonical(parts)).encode()).hexdigest()[:24]
        return self.cache_dir / f"{stage}-{h}.json"

    @staticmethod
    def _cload(p: Path | None):
        if p and p.is_file():
            return json.loads(p.read_text(encoding="utf-8"))
        return None

    @staticmethod
    def _csave(p: Path | None, obj) -> None:
        if p:
            tmp = p.with_name(p.name + ".tmp")
            tmp.write_text(json.dumps(obj, ensure_ascii=False), encoding="utf-8")
            tmp.replace(p)

    def _relabel(self, x, words, stock_turns, audio_key):
        """diarization.relabel: speakers from ASR phrases (relabel.py); the stock turns only give cut points."""
        p = RelabelParams.from_config(self.relabel)
        w = [(a.start_ms, a.end_ms) for a in words]
        tt = [(t.speaker, t.start_ms, t.end_ms) for t in stock_turns] or None
        units = make_units(w, tt, p)
        if not units:
            return stock_turns, {"units": 0}
        ck = self._ckey("emb", audio_key, {"u": [(u[0], u[1]) for u in units], "m": self.cfg["diarization"]["embedding"],
                                           "pad": p.pad_ms, "max": p.embed_max_s, "gain": self._diar_gain()})
        hit = None
        if ck and ck.with_suffix(".npy").is_file():
            hit = np.load(ck.with_suffix(".npy"))
        if hit is not None:
            E = hit
        else:
            E = embed_units(x, units, self.embedder, p)
            if ck:
                np.save(ck.with_suffix(".npy"), E)
        dur = np.array([u[1] - u[0] for u in units])
        diag: dict = {}
        lab = label_units(E, dur, p, diag, stock_is_single(tt, p.k1_stock_min_share),
                          np.array([[u[0], u[1]] for u in units]))
        out = [SpeakerTurn(int(l), u[0], u[1]) for u, l in zip(units, lab)]
        return out, {"units": len(units), "k": int(len(set(int(l) for l in lab))),
                     "silhouettes": {str(k): round(v, 4) for k, v in diag.get("silhouettes", {}).items()},
                     "k1": bool(diag.get("k1", False)), "cos2": diag.get("cos2"), "cos_best": diag.get("cos_best")}

    def _diar_gain(self):
        """Diarization only depends on the gain config when the whole signal is normalised (scope all)."""
        ng = self.cfg["audio"].get("normalize_gain") or {}
        return ng if ng.get("enabled") and ng.get("scope", "all") == "all" else None

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
        key = hashlib.sha256(Path(path).read_bytes()).hexdigest()[:20] if self.cache_dir else None
        return self.process_audio(x, num_speakers, decode_ms=decode_ms, t_start=t0, audio_key=key)

    def process_audio(self, x: np.ndarray, num_speakers: int | None = None, decode_ms: int = 0,
                      t_start: float | None = None, audio_key: str | None = None) -> Result:
        sampler = RssSampler()
        sampler.start()
        try:
            res = self._process_audio(x, num_speakers, decode_ms, t_start, audio_key)
        finally:
            peak = sampler.finish()
        res.peak_rss_mb = round(peak, 1)
        return res

    def close(self) -> None:
        if self.diar is not None and hasattr(self.diar, "close"):
            self.diar.close()

    def _process_audio(self, x: np.ndarray, num_speakers: int | None, decode_ms: int,
                       t_start: float | None, audio_key: str | None = None) -> Result:
        t_start = t_start if t_start is not None else time.perf_counter()
        cfg = self.cfg
        stage: dict[str, int] = {"decode": decode_ms}
        info: dict = {"punctuation_source": self.punct_source}
        dur_ms = len(x) * 1000 // SR
        if dur_ms == 0:
            return Result([], [], [], [], [], 0, stage, _ms(t_start), info)

        t = time.perf_counter()
        ng = cfg["audio"].get("normalize_gain") or {}
        scope = ng.get("scope", "all") if ng.get("enabled") else "all"
        x_asr, chunk_gain, seg_gain = None, None, None
        if scope == "all":          # every stage sees the normalised signal
            x, gain_db = normalize_gain(x, ng)
        elif scope == "asr":        # VAD / rescue / diarization see the ORIGINAL signal, ASR+LID the normalised copy
            x_asr, gain_db = normalize_gain(x, ng)
        elif scope == "asr_chunk":  # every ASR chunk is peak-normalised on its own (gain from the chunk's peak)
            gain_db, chunk_gain = 0.0, ng
        elif scope == "asr_segment":  # every VAD segment is peak-normalised on its own, ASR input only
            gain_db, seg_gain = 0.0, ng
        else:
            raise ValueError(f"unknown normalize_gain.scope {scope!r}")
        if x_asr is None:
            x_asr = x
        if gain_db:
            info["gain_db"] = round(gain_db, 2)
        stage["gain"] = _ms(t)

        dn = cfg["audio"]["denoise"]
        t = time.perf_counter()
        if dn.get("enabled") and dn.get("model"):
            snr = estimate_snr_db(x)
            info["snr_db"] = None if snr == float("inf") else round(snr, 2)
            if snr < float(dn.get("snr_threshold_db", 12.0)):
                src = x_asr if dn.get("scope") == "asr" else x   # scope asr: only the ASR/LID input is denoised
                y = denoise(src, self.root / dn["model"], self.threads)
                y = np.pad(y, (0, max(0, len(x) - len(y))))[: len(x)].astype(np.float32)
                if dn.get("scope") == "asr":
                    x_asr = y
                else:
                    x = y
                    if x_asr is not None and scope != "asr":
                        x_asr = x
                info["denoised"] = True
        stage["denoise"] = _ms(t)

        c = cfg
        asr_parts = {k: c.get(k) for k in ("vad", "asr", "asr_en", "language")}
        asr_parts["denoise"] = c["audio"]["denoise"]
        asr_parts["gain"] = c["audio"].get("normalize_gain")
        asr_parts["agc"] = c["audio"].get("agc")
        # only drop_tiny_chunks changes the cached ASR output; numbers / strip_dashes are applied after the cache
        asr_parts["post"] = {"numbers": "as_is", "drop_tiny_chunks": (c.get("postprocess") or {}).get("drop_tiny_chunks")}
        asr_parts["keep_punct"] = self.keep_punct
        asr_cache = self._ckey("asr", audio_key, asr_parts)
        diar_cache = self._ckey("diar", audio_key, {"d": c["diarization"], "n": num_speakers,
                                                    "denoise": c["audio"]["denoise"],
                                                    "gain": self._diar_gain()})

        agc_cfg = (cfg["audio"].get("agc") or {})
        if agc_cfg.get("enabled"):
            x_asr = agc(x_asr, agc_cfg)
            info["agc"] = True

        def run_asr():
            hit = self._cload(asr_cache)
            if hit:
                info["language"] = hit["language"]
                info.update(hit.get("info", {}))
                info.setdefault("cached_stages", []).append("asr")
                return ([TimeSpan(*t) for t in hit["speech"]], [TimeSpan(*t) for t in hit["chunks"]],
                        [Word(*w) for w in hit["words"]], 0, 0, 0, 0)
            t1 = time.perf_counter()
            speech, chunks = speech_chunks(x, cfg, self.vad)
            vad_ms = _ms(t1)
            xa = segment_gain(x_asr, [(a_.start_ms, a_.end_ms) for a_ in speech], seg_gain) if seg_gain else x_asr
            t_l = time.perf_counter()
            langs = ["ru"] * len(chunks)
            raw_langs: list = []
            lang, raw = "ru", None
            if self.lang_mode == "en":
                lang, langs = "en", ["en"] * len(chunks)
            elif self.lid is not None and speech and self.per_chunk:
                # per chunk (Android LanguageSmoother): LID of the first lid_seconds of every chunk >= 1.5 s, state
                # starts at ru, no file-level LID
                raw_langs = [self.lid.detect_chunk(xa, c)[0] for c in chunks]
                langs = smooth_languages(raw_langs, [c.dur_ms for c in chunks], self.switch_min_ms)
                en_ms = sum(c.dur_ms for c, l_ in zip(chunks, langs) if l_ == "en")
                lang = "en" if en_ms > sum(c.dur_ms for c in chunks) - en_ms else "ru"
            elif self.lid is not None and speech:
                lang, raw = self.lid.detect(xa, speech)  # file level: first lid_seconds of speech
                langs = [lang] * len(chunks)
            lid_ms = _ms(t_l)
            info["language"] = lang
            info["language_detected"] = raw
            if self.per_chunk and self.lid is not None:
                info["language_by_chunk"] = "".join("e" if l == "en" else "r" for l in langs)
            t2 = time.perf_counter()
            words: list[Word] = []
            if chunks:
                # consecutive chunks of the same language go to the engine together (batch_size applies)
                i = 0
                while i < len(chunks):
                    j = i
                    while j < len(chunks) and langs[j] == langs[i]:
                        j += 1
                    engine = self.asr
                    if langs[i] == "en":
                        if self.asr_en is None:
                            t_e = time.perf_counter()
                            self.asr_en = AsrEngine(deep_merge(DEFAULTS["asr"], cfg["asr_en"]), self.root, self.threads)
                            info["asr_en_load_ms"] = _ms(t_e)
                        engine = self.asr_en
                    words.extend(engine.transcribe(xa, chunks[i:j], keep_punct=self.keep_punct,
                                                   drop_tiny_chars=self.drop_tiny, chunk_gain=chunk_gain))
                    i = j
            asr_ms = _ms(t2)
            sp_ms = 0
            if self.second and words and "en" not in langs:
                t3 = time.perf_counter()
                words, info["second_pass_chunks"] = self.second.apply(xa, chunks, words, self.keep_punct)
                sp_ms = _ms(t3)
            self._csave(asr_cache, {"language": lang, "info": {k: v for k, v in info.items() if k.startswith("language")}, "speech": [[s.start_ms, s.end_ms] for s in speech],
                                    "chunks": [[s.start_ms, s.end_ms] for s in chunks],
                                    "words": [[w.text, w.start_ms, w.end_ms, w.confidence] for w in words]})
            return speech, chunks, words, vad_ms, asr_ms, sp_ms, lid_ms

        def run_diar():
            hit = self._cload(diar_cache)
            if hit is not None:
                info.setdefault("cached_stages", []).append("diarization")
                return [SpeakerTurn(*t) for t in hit], 0
            t1 = time.perf_counter()
            turns = self.diar.diarize(x, num_speakers) if self.diar else []
            self._csave(diar_cache, [[t.speaker, t.start_ms, t.end_ms] for t in turns])
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

        if (cfg.get("postprocess") or {}).get("numbers") == "words":
            from .postprocess import spell_numbers

            words = spell_numbers(words)
        sd = (cfg.get("postprocess") or {}).get("strip_dashes", "off")
        if sd not in (None, "off"):
            from .postprocess import strip_dashes

            words = strip_dashes(words, sd)

        if self.relabel and words:
            t = time.perf_counter()
            turns, rl_info = self._relabel(x, words, turns, audio_key)
            info["relabel"] = rl_info
            stage["relabel"] = _ms(t)

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
