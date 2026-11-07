# Исходный код

| Путь | Что |
|---|---|
| `android/` | приложение (Kotlin, Jetpack Compose, sherpa-onnx, llama.cpp): модули `core`, `audio`, `engine-sherpa`, `engine-llm`, `app` — `android/README.md`, `android/app/README.md` |
| `eval/stenoeval/` | метрики и проверка формата: WER, cpWER, DER, метки, RTF |
| `eval/refpipe/` | Python-эталон пайплайна на тех же моделях и конфиге |
| `eval/datasets/` | сборка тестовых наборов |
| `eval/bench_asr/` | сравнение ASR-моделей |

## Сборка и запуск

Полная инструкция — `docs/INSTALL.md` (раздел «Сборка из исходников»). Коротко, из корня репозитория:

```bash
source scripts/env.sh                           # JDK 17, Android SDK
python3 scripts/fetch_models.py --tier medium   # модели -> .cache/models
scripts/stage_models.sh medium                  # модели -> assets варианта
scripts/build_all.sh                            # APK -> artifacts/apk/
cd src/android && ./gradlew :core:test          # тесты ядра
```
Python-инструменты: `cd src/eval/<пакет> && uv venv --python 3.11 .venv && uv pip install --python .venv/bin/python -e ".[dev]"`.
