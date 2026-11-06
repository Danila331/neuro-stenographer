"""Stage DIARIZATION: sherpa-onnx OfflineSpeakerDiarization (pyannote-3.0 segmentation + embedding
+ fast clustering).  Returns speaker turns (ms, raw 0-based cluster ids; turns may be adjacent)."""
from __future__ import annotations

from pathlib import Path

import numpy as np

from .types import SpeakerTurn

SR = 16000


def _config(dcfg: dict, models_root: Path, num_threads: int, num_clusters: int):
    import sherpa_onnx as so

    clu = dcfg["clustering"]
    return so.OfflineSpeakerDiarizationConfig(
        segmentation=so.OfflineSpeakerSegmentationModelConfig(
            pyannote=so.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=str(models_root / dcfg["segmentation"]),
                window_shift_ratio=float(dcfg.get("window_shift_ratio", 0.1))),
            num_threads=num_threads),
        embedding=so.SpeakerEmbeddingExtractorConfig(
            model=str(models_root / dcfg["embedding"]), num_threads=num_threads),
        clustering=so.FastClusteringConfig(num_clusters=num_clusters, threshold=float(clu["threshold"])),
        min_duration_on=float(dcfg["min_duration_on_s"]),
        min_duration_off=float(dcfg["min_duration_off_s"]),
    )


def _run(sd, x: np.ndarray) -> list[SpeakerTurn]:
    res = sd.process(x).sort_by_start_time()
    return [SpeakerTurn(int(s.speaker), int(s.start * 1000 + 0.5), int(s.end * 1000 + 0.5)) for s in res]


class Diarizer:
    def __init__(self, dcfg: dict, models_root: Path, num_threads: int):
        import sherpa_onnx as so

        self.dcfg, self.root, self.threads = dcfg, models_root, num_threads
        n = dcfg["clustering"].get("num_speakers")
        self.default_n = int(n) if n else -1
        self._so = so
        self.sd = so.OfflineSpeakerDiarization(_config(dcfg, models_root, num_threads, self.default_n))

    def diarize(self, x: np.ndarray, num_speakers: int | None = None) -> list[SpeakerTurn]:
        """num_speakers (CLI/manifest) > config clustering.num_speakers > threshold clustering.
        In threshold mode, more than clustering.max_speakers clusters triggers a second clustering
        pass with num_clusters = max_speakers."""
        n = int(num_speakers) if num_speakers else self.default_n
        if n != self.default_n:
            self.sd.set_config(_config(self.dcfg, self.root, self.threads, n))
        try:
            if len(x) < SR // 2:
                return []
            turns = _run(self.sd, x)
        finally:
            if n != self.default_n:
                self.sd.set_config(_config(self.dcfg, self.root, self.threads, self.default_n))
        mx = int(self.dcfg["clustering"].get("max_speakers") or 0)
        if n <= 0 and mx and len({t.speaker for t in turns}) > mx:
            self.sd.set_config(_config(self.dcfg, self.root, self.threads, mx))
            try:
                turns = _run(self.sd, x)
            finally:
                self.sd.set_config(_config(self.dcfg, self.root, self.threads, self.default_n))
        return turns


# --- subprocess isolation -------------------------------------------------------------------
# sherpa-onnx's Python binding holds the GIL during OfflineSpeakerDiarization.process (verified:
# a spinning thread gets ~0 ticks), which would serialise it with the ASR thread.  So the Diarizer
# lives in a one-process pool (spawn) and is fed with the audio; ASR/VAD (which do release the GIL)
# keep running in the main process.  Results are identical to the in-process call.
_WORKER: Diarizer | None = None


def _worker_init(dcfg, root, threads):
    global _WORKER
    _WORKER = Diarizer(dcfg, Path(root), threads)


def _worker_run(x, num_speakers):
    return [(t.speaker, t.start_ms, t.end_ms) for t in _WORKER.diarize(x, num_speakers)]


class SubprocessDiarizer:
    def __init__(self, dcfg: dict, models_root: Path, num_threads: int):
        import multiprocessing as mp
        from concurrent.futures import ProcessPoolExecutor

        self.pool = ProcessPoolExecutor(1, mp_context=mp.get_context("spawn"), initializer=_worker_init,
                                        initargs=(dcfg, str(models_root), num_threads))
        self.pool.submit(int, 0).result()  # start the worker and load the models now

    def diarize(self, x: np.ndarray, num_speakers: int | None = None) -> list[SpeakerTurn]:
        return [SpeakerTurn(*t) for t in self.pool.submit(_worker_run, x, num_speakers).result()]

    def worker_pid(self) -> int | None:
        procs = getattr(self.pool, "_processes", None) or {}
        return next(iter(procs), None)

    def close(self):
        self.pool.shutdown(wait=True)
