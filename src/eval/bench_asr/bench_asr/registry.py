"""Реестр кандидатов (ключ -> Spec). Папки в $ROOT/.cache/models (качает scripts/fetch_models.sh)."""
from .engines import Spec

N = "sherpa-onnx-"
_S = [
    # GigaAM (NeMo-совместимый экспорт, char-level словарь 33 буквы; в релизе только int8-энкодер)
    Spec("gigaam2_ctc", N + "nemo-ctc-giga-am-v2-russian-2025-04-19", "nemo_ctc", "int8"),
    Spec("gigaam2_rnnt", N + "nemo-transducer-giga-am-v2-russian-2025-04-19", "nemo_transducer", "int8"),
    Spec("gigaam3_ctc", N + "nemo-ctc-giga-am-v3-russian-2025-12-16", "nemo_ctc", "int8"),
    Spec("gigaam3_rnnt", N + "nemo-transducer-giga-am-v3-russian-2025-12-16", "nemo_transducer", "int8"),
    Spec("gigaam3_ctc_punct", N + "nemo-ctc-punct-giga-am-v3-russian-2025-12-16", "nemo_ctc", "int8", punct=True),
    Spec("gigaam3_rnnt_punct", N + "nemo-transducer-punct-giga-am-v3-russian-2025-12-16", "nemo_transducer", "int8",
         punct=True),
    # Zipformer ru (k2/icefall, BPE)
    Spec("zip_ru_2504_int8", N + "zipformer-ru-int8-2025-04-20", "transducer", "int8"),
    Spec("zip_ru_2504_fp32", N + "zipformer-ru-2025-04-20", "transducer", "fp32"),
    Spec("zip_small_2409", N + "small-zipformer-ru-2024-09-18", "transducer", "int8"),
    Spec("zip_small_2409_fp32", N + "small-zipformer-ru-2024-09-18", "transducer", "fp32"),
    Spec("vosk_small_stream", N + "streaming-zipformer-small-ru-vosk-int8-2025-08-16", "online_transducer", "int8"),
    # --- мультиязычные / английские кандидаты (ru+en) ---
    Spec("fc_multi_ctc", N + "nemo-fast-conformer-ctc-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8", "nemo_ctc", "int8", note="ru+en+8 др."),
    Spec("fc_multi_rnnt", N + "nemo-fast-conformer-transducer-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8", "nemo_transducer", "int8"),
    Spec("parakeet_v3", N + "nemo-parakeet-tdt-0.6b-v3-int8", "nemo_transducer", "int8", note="25 языков, в т.ч. ru и en", punct=True),
    Spec("parakeet_v2_en", N + "nemo-parakeet-tdt-0.6b-v2-int8", "nemo_transducer", "int8", note="только en", punct=True),
    Spec("parakeet110_en", N + "nemo-parakeet_tdt_transducer_110m-en-36000-int8", "nemo_transducer", "int8", note="только en"),
    Spec("zip_small_en", N + "zipformer-small-en-2023-06-26", "transducer", "int8", note="только en"),
    Spec("moonshine_base_en", N + "moonshine-base-en-quantized-2026-02-27", "moonshine_v2", "int8", note="только en"),
    Spec("moonshine_tiny_en", N + "moonshine-tiny-en-quantized-2026-02-27", "moonshine_v2", "int8", note="только en"),
    Spec("whisper_tiny_int8", N + "whisper-tiny", "whisper", "int8"),
    Spec("whisper_base_int8", N + "whisper-base", "whisper", "int8"),
    # T-one (T-Tech, streaming CTC)
    Spec("t_one", N + "streaming-t-one-russian-2025-09-08", "t_one", "int8"),
    # Whisper
    Spec("whisper_turbo_int8", "asr/whisper-large-v3-turbo-int8", "whisper", "int8"),
    Spec("whisper_small_int8", N + "whisper-small", "whisper", "int8"),
    Spec("whisper_small_fp32", N + "whisper-small", "whisper", "fp32"),
    Spec("whisper_medium_int8", N + "whisper-medium", "whisper", "int8"),
    Spec("whisper_large_v3_int8", N + "whisper-large-v3", "whisper", "int8"),
]
REGISTRY = {s.key: s for s in _S}
# refpipe кладёт тот же экспорт GigaAM v3 RNNT в asr/gigaam-v3-rnnt-int8 — переиспользуем, если своя копия ещё не скачана
_alt = REGISTRY["gigaam3_rnnt"]
if not _alt.path.exists() and (_alt.path.parent / "asr" / "gigaam-v3-rnnt-int8").exists():
    _alt.dirname = "asr/gigaam-v3-rnnt-int8"
