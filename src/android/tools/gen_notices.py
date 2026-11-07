#!/usr/bin/env python3
"""Генератор assets/third_party_notices.json -- реестра сторонних компонентов для экрана «Лицензии и модели» (Настройки).

Версии библиотек берутся из gradle/libs.versions.toml, список поставляемых моделей -- из config/models.json (tiers lite/medium/hard,
кандидаты и тестовые зависимости в реестр не входят: в дистрибутив они не попадают). Полные тексты лицензий -- app/src/main/assets/licenses/;
`text_complete = false` -- вложена только справка со ссылкой (нет локального текста). Сверка с docs/LICENSES.md обязательна при изменении.

    python3 src/android/tools/gen_notices.py
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = HERE.parent / "app/src/main/assets/third_party_notices.json"
LICENSES = HERE.parent / "app/src/main/assets/licenses"

toml = (HERE.parent / "gradle/libs.versions.toml").read_text(encoding="utf-8")
V = dict(re.findall(r'^([\w-]+)\s*=\s*"([^"]+)"', toml, re.M))
models_json = json.loads((ROOT / "config/models.json").read_text(encoding="utf-8"))
shipped_dirs = sorted({"/".join(m["path"].split("/")[:2]) for m in models_json["models"] if set(m.get("tiers", [])) & {"lite", "medium", "hard"}})

APACHE = ("Apache-2.0", "Apache-2.0.txt")
MIT = ("MIT", "MIT.txt")


def entry(kind, id_, name, version, purpose, lic, source, note="", tiers=None, complete=True):
    license_id, file = lic
    assert (LICENSES / file).is_file(), f"нет файла лицензии {file}"
    e = {"id": id_, "kind": kind, "name": name, "version": version, "purpose": purpose, "license": license_id, "license_file": file,
         "text_complete": complete, "source": source}
    if note:
        e["note"] = note
    if tiers:
        e["tiers"] = tiers
    return e


M, L, F = "model", "library", "font"
items = [
    # ---------------------------------------------------------------- модели (поставляются в наборах lite/medium/hard)
    entry(M, "asr/gigaam-v3-rnnt-punct-int8", "GigaAM v3 RNNT (с пунктуацией, int8)", "2025-12-16", "Распознавание русской речи, знаки и регистр",
          ("MIT", "MIT-GigaAM.txt"), "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16",
          "salute-developers/GigaAM; ONNX int8 -- k2-fsa", ["lite", "medium", "hard"]),
    entry(M, "asr/parakeet-tdt-110m-en-int8", "NVIDIA Parakeet-TDT 110M (English, int8)", "36000 steps", "Распознавание английской речи",
          ("CC-BY-4.0", "CC-BY-4.0.txt"), "https://huggingface.co/nvidia/parakeet_tdt_ctc_110m",
          "Атрибуция: NVIDIA; изменения: квантизация int8 и экспорт ONNX (k2-fsa). Вложена справка со ссылкой, не полный юридический текст.", ["lite", "medium", "hard"], complete=False),
    entry(M, "asr/streaming-zipformer-small-ru-int8", "vosk-model-small-streaming-ru (zipformer, int8)", "2025-08-16", "Живая расшифровка во время записи (превью)",
          APACHE, "https://huggingface.co/alphacep/vosk-model-small-streaming-ru", "alphacep/vosk; ONNX int8 -- csukuangfj (sherpa-onnx)", ["lite", "medium", "hard"]),
    entry(M, "asr/whisper-tiny-int8", "Whisper tiny, multilingual (int8)", "tiny", "Определение языка речи (русский/английский)",
          MIT, "https://huggingface.co/openai/whisper-tiny", "OpenAI Whisper; ONNX int8 -- csukuangfj (sherpa-onnx)", ["lite", "medium", "hard"]),
    entry(M, "silero-vad/silero_vad.onnx", "Silero VAD", "v5", "Поиск речи в записи (VAD)", MIT,
          "https://github.com/snakers4/silero-vad", "snakers4/silero-vad", ["lite", "medium", "hard"]),
    entry(M, "diar/pyannote-segmentation-3.0", "pyannote segmentation 3.0 (int8)", "3.0", "Разделение говорящих: сегментация",
          ("MIT", "MIT-pyannote.txt"), "https://huggingface.co/pyannote/segmentation-3.0", "ONNX-экспорт k2-fsa (sherpa-onnx)", ["lite", "medium", "hard"]),
    entry(M, "diar/emb-campplus-zh-cn", "3D-Speaker CAM++ (голосовые отпечатки)", "16k", "Голосовые отпечатки говорящих (lite)", APACHE,
          "https://github.com/modelscope/3D-Speaker", "modelscope/3D-Speaker; релиз sherpa-onnx", ["lite"]),
    entry(M, "diar/emb-eres2net-base-200k", "3D-Speaker ERes2Net base 200k (голосовые отпечатки)", "16k", "Голосовые отпечатки говорящих (medium)", APACHE,
          "https://github.com/modelscope/3D-Speaker", "modelscope/3D-Speaker; релиз sherpa-onnx", ["medium"]),
    entry(M, "diar/emb-eres2netv2", "3D-Speaker ERes2Net v2 (голосовые отпечатки)", "16k", "Голосовые отпечатки говорящих (hard)", APACHE,
          "https://github.com/modelscope/3D-Speaker", "modelscope/3D-Speaker; релиз sherpa-onnx", ["hard"]),
    entry(M, "denoise/gtcrn_simple.onnx", "GTCRN (шумоподавление)", "simple", "Шумоподавление на тихих и шумных записях (hard)", MIT,
          "https://github.com/Xiaobin-Rong/gtcrn", "Xiaobin-Rong/gtcrn; релиз sherpa-onnx", ["hard"]),
    entry(M, "embed/user2-small", "USER2-small (эмбеддер поиска, int8)", "small", "AI-поиск: смысловые векторы фрагментов (medium, hard)", APACHE,
          "https://huggingface.co/deepvk/USER2-small", "deepvk/USER2-small; ONNX int8 -- наш экспорт (bench_rag.export_onnx)", ["medium", "hard"]),
    entry(M, "embed/rubert-tiny-turbo", "rubert-tiny-turbo (эмбеддер поиска, int8)", "turbo", "AI-поиск: смысловые векторы фрагментов (lite)", MIT,
          "https://huggingface.co/sergeyzh/rubert-tiny-turbo", "sergeyzh/rubert-tiny-turbo; ONNX int8 -- наш экспорт (bench_rag.export_onnx)", ["lite"]),
    entry(M, "llm/qwen3-0.6b-q4_0.gguf", "Qwen3 0.6B (Q4_0)", "0.6B", "Протокол, вопросы, выжимка, ответы поиска (lite)", APACHE,
          "https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF", "Qwen (Alibaba Cloud); GGUF -- ggml-org", ["lite"]),
    entry(M, "llm/qwen3-1.7b-q4_k_m.gguf", "Qwen3 1.7B (Q4_K_M)", "1.7B", "Протокол, вопросы, выжимка, ответы поиска (medium)", APACHE,
          "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF", "Qwen (Alibaba Cloud); GGUF -- ggml-org", ["medium"]),
    entry(M, "llm/qwen3-4b-q4_k_m.gguf", "Qwen3 4B (Q4_K_M)", "4B", "Протокол, вопросы, выжимка, ответы поиска (hard)", APACHE,
          "https://huggingface.co/Qwen/Qwen3-4B-GGUF", "Qwen (Alibaba Cloud)", ["hard"]),
    # ---------------------------------------------------------------- библиотеки (входят в приложение)
    entry(L, "sherpa-onnx", "sherpa-onnx (AAR)", V["sherpa-onnx"], "Движок распознавания речи, VAD, диаризации и голосовых отпечатков", APACHE,
          "https://github.com/k2-fsa/sherpa-onnx"),
    entry(L, "onnxruntime-lib", "ONNX Runtime (libonnxruntime.so из sherpa-onnx)", "1.28.x", "Выполнение ONNX-моделей; общая библиотека для sherpa-onnx и эмбеддера поиска",
          ("MIT", "MIT-onnxruntime.txt"), "https://github.com/microsoft/onnxruntime"),
    entry(L, "onnxruntime-headers", "ONNX Runtime C API (заголовки)", "1.28.x", "Заголовки для нативного эмбеддера поиска (engine-embed); только при сборке",
          ("MIT", "MIT-onnxruntime.txt"), "https://github.com/microsoft/onnxruntime"),
    entry(L, "llama.cpp", "llama.cpp", V["llama-cpp-tag"] + " (архив v0.9.0)", "Языковая модель на устройстве: протокол, вопросы, выжимка, ответы поиска",
          ("MIT", "MIT-llama.cpp.txt"), "https://github.com/ggml-org/llama.cpp"),
    entry(L, "objectbox-api", "ObjectBox (Java/Kotlin API)", V["objectbox"], "Хранилище и векторный индекс HNSW для AI-поиска", APACHE,
          "https://github.com/objectbox/objectbox-java", "Генератор кода (objectbox-processor) используется только при сборке и в приложение не входит."),
    entry(L, "objectbox-native", "ObjectBox (нативная библиотека libobjectbox-jni.so)", V["objectbox"], "Нативная часть хранилища ObjectBox",
          ("ObjectBox (бинарная лицензия)", "ObjectBox-binary.txt"), "https://objectbox.io/faq/",
          "Закрытая библиотека ObjectBox GmbH; при отказе от неё поиск работает на встроенном хранилище в памяти. Вложена справка, не полный текст.", complete=False),
    entry(L, "kotlin-stdlib", "Kotlin (стандартная библиотека)", V["kotlin"], "Язык и библиотека приложения", APACHE, "https://github.com/JetBrains/kotlin"),
    entry(L, "kotlinx-coroutines", "kotlinx.coroutines (core, android)", V["coroutines"], "Асинхронная работа и потоки данных", APACHE, "https://github.com/Kotlin/kotlinx.coroutines"),
    entry(L, "kotlinx-serialization-json", "kotlinx.serialization (JSON)", V["serialization"], "Чтение и запись JSON (результаты, конфигурация)", APACHE, "https://github.com/Kotlin/kotlinx.serialization"),
    entry(L, "androidx-core-ktx", "AndroidX Core KTX", V["core-ktx"], "Базовые расширения Android", APACHE, "https://developer.android.com/jetpack/androidx/releases/core"),
    entry(L, "androidx-activity-compose", "AndroidX Activity Compose", V["activity-compose"], "Окно приложения и результаты системных диалогов", APACHE, "https://developer.android.com/jetpack/androidx/releases/activity"),
    entry(L, "androidx-lifecycle", "AndroidX Lifecycle (runtime, ViewModel, service)", V["lifecycle"], "Жизненный цикл экранов, состояние и фоновая служба", APACHE, "https://developer.android.com/jetpack/androidx/releases/lifecycle"),
    entry(L, "androidx-work", "AndroidX WorkManager", V["work"], "Фоновая загрузка наборов моделей и индексация поиска", APACHE, "https://developer.android.com/jetpack/androidx/releases/work"),
    entry(L, "compose", "Jetpack Compose (UI, Foundation, Material 3, иконки)", V["compose-bom"] + " (BOM)", "Интерфейс приложения", APACHE, "https://developer.android.com/jetpack/androidx/releases/compose"),
    entry(L, "commons-compress", "Apache Commons Compress", V["commons-compress"], "Распаковка архивов наборов моделей при загрузке", APACHE, "https://commons.apache.org/proper/commons-compress/",
          "Входит в модуль загрузки моделей."),
    # ---------------------------------------------------------------- шрифты (вшиты в APK)
    entry(F, "font/unbounded", "Unbounded", "variable (wght 200-900)", "Заголовки интерфейса", ("OFL-1.1", "OFL-unbounded.txt"), "https://github.com/googlefonts/unbounded",
          "Copyright 2022 The Unbounded Project Authors"),
    entry(F, "font/golos-text", "Golos Text", "variable (wght 400-900)", "Основной текст интерфейса", ("OFL-1.1", "OFL-golostext.txt"), "https://github.com/googlefonts/golos-text",
          "Copyright 2019 The Golos Text Project Authors"),
    entry(F, "font/jetbrains-mono", "JetBrains Mono", "variable (wght 100-800)", "Таймкоды и числа", ("OFL-1.1", "OFL-jetbrainsmono.txt"), "https://github.com/JetBrains/JetBrainsMono",
          "Copyright 2020 The JetBrains Mono Project Authors"),
]

ids = [i["id"] for i in items]
assert len(ids) == len(set(ids)), "повторяющиеся id"
model_dirs = {i["id"].rsplit("/", 1)[0] if i["id"].count("/") > 1 else i["id"] for i in items if i["kind"] == M}
missing = [d for d in shipped_dirs if not any(i["id"].startswith(d) for i in items if i["kind"] == M)]
if missing:
    sys.exit(f"в config/models.json есть поставляемые модели без записи в реестре: {missing}")
OUT.write_text(json.dumps(items, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"{len(items)} компонентов -> {OUT.relative_to(ROOT)}")
