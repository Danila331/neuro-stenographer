# Android-приложение «Нейро-стенографист»

Gradle-проект (Kotlin 2.4, AGP 9.5, Compose). Полностью офлайн: импорт аудио → распознавание (sherpa-onnx) →
диаризация → выравнивание → пунктуация (llama.cpp) → показ и экспорт JSON строго по ТЗ.
Контракты — `docs/ARCHITECTURE.md`; конфиги вариантов — `config/tiers/*.json` (их же читает Python-эталон).

## Требования и окружение

| Что | Версия | Где лежит |
|---|---|---|
| JDK | 17 (Temurin) | `$ROOT/.local/jdk17` |
| Gradle | 9.6.0 (wrapper, sha256 зафиксирован) | `$ROOT/.local/gradle` |
| Android SDK | platform 36 (compile), 35 (target), build-tools 36.0.0, NDK 28.2.13676358, CMake 3.22.1, platform-tools, emulator, system-image arm64 | `$ROOT/.local/android-sdk` |
| cmake/ninja (хост, только для проверки JNI) | pip-колёса в `$ROOT/.local/pytools` | `$ROOT/.local/bin` |

`$ROOT` — корень основного репозитория; скрипты находят его через `git rev-parse --git-common-dir`, поэтому работают
из любой рабочей копии (`git worktree`).

```bash
source scripts/env.sh        # JAVA_HOME, ANDROID_HOME/ANDROID_SDK_ROOT, ANDROID_USER_HOME, ANDROID_AVD_HOME,
                             # GRADLE_USER_HOME, PATH (platform-tools, emulator, cmdline-tools, .local/bin)
```

## Быстрый старт

```bash
source scripts/env.sh
cd src/android
./gradlew :core:test                      # работает с одним JDK, Android SDK не нужен
```

Режимы сборки (выбираются в `settings.gradle.kts`):

* **только `:core`** — по умолчанию, пока в `$ANDROID_HOME` нет `platforms/android-36` и `build-tools`. Android-модули
  не подключаются, AGP не загружается. Корневой скрипт — `build.gradle.kts`.
* **с Android** — автоматически, когда SDK готов, либо принудительно `-Pneurosteno.android=true`
  (`-Pneurosteno.android=false` отключает). Корневой скрипт — `build.android.gradle.kts`: плагины Kotlin и AGP объявлены
  вместе (`apply false`), иначе Kotlin Gradle Plugin и AGP оказываются в разных classloader'ах
  (`NoClassDefFoundError: BaseVariant`).

### Android-часть (нужен SDK)

SDK ставится один раз; **лицензии принимает пользователь сам** (`sdkmanager --licenses`), скрипты их не принимают:

```bash
source scripts/env.sh
sdkmanager --licenses                    # интерактивно, решение пользователя
src/android/scripts/sdk_setup.sh         # platform-tools, platforms;android-36/35, build-tools;36.0.0, ndk;28.2.13676358,
                                         # cmake;3.22.1, emulator, system-images;android-35;google_apis;arm64-v8a
src/android/scripts/create_avds.sh       # AVD jury-4g (4 ГБ, 4 ядра) и jury-2g (2 ГБ, 2 ядра), swiftshader, без GPU хоста
src/android/scripts/fetch_sherpa_onnx.sh # prebuilt AAR sherpa-onnx 2.0.1 → third_party/maven (sha256 проверяется)
src/android/scripts/fetch_llama_cpp.sh   # исходники llama.cpp v0.9.0 → engine-llm/src/main/cpp/llama.cpp (sha256)

scripts/stage_models.sh medium           # модели из $ROOT/.cache/models → app/src/medium/assets/models/medium + manifest.json
cd src/android && ./gradlew :app:assembleMediumDebug
scripts/build_all.sh                     # три (+online) release-APK, подпись локальным ключом → artifacts/apk/
scripts/emulator.sh jury-4g              # headless, ждёт sys.boot_completed;  scripts/emulator.sh stop
src/android/scripts/smoke_apk.sh artifacts/apk/neurosteno-medium-0.1.0.apk
```

Правила нагрузки: Gradle ≤ 3 воркеров (`gradle.properties`), один эмулятор за раз (`scripts/emulator.sh` отказывается
запускать второй), по окончании `./gradlew --stop` и `scripts/emulator.sh stop`.

### Проверено на эмуляторе (jury-4g / jury-2g)

```bash
scripts/emulator.sh jury-4g                                    # headless, ждёт boot_completed
src/android/scripts/smoke_apk.sh <apk>                         # установка, запуск, logcat без FATAL
src/android/scripts/process_on_device.sh <apk> <audio> [out]   # настоящая обработка: VIEW-интент (автостарт),
                                                               # JSON из Android/data/<pkg>/files/results, пик PSS/RSS, время
(cd src/eval/stenoeval && uv run stenoeval validate <out>/<file>.json)
scripts/emulator.sh stop
```
Приложение само сохраняет результат (`<имя>.json` и `<имя>.full.json`) в свою внешнюю папку
`Android/data/<pkg>/files/results/` и пишет в logcat (тег `Neurosteno`) строку `done ...` с RTF и временем стадий.
`stage_models.sh --map <путь в конфиге>=<другой путь в кэше>` подставляет имеющийся файл вместо отсутствующего
(только для проверок: имена в кэше и в конфигах пока различаются). Необязательные модели (английская ASR, LID, денойз, LLM)
при отсутствии отключаются с предупреждением; обязательны VAD, основная ASR и диаризация.
Известно: `./gradlew :app:tasks --all` падает на `generate*ComposePreviewRunfiles` (релизные варианты без юнит-тестов),
на сборку `assemble*` это не влияет.

### Паритет Android <-> refpipe

```bash
src/android/scripts/parity.sh ru-dialog-tts-cats 10 medium   # N dev-файлов через tools/jvm-smoke и через refpipe, stenoeval score
src/android/scripts/parity.sh ru-dialog-synth 5 medium       # падает, если WER norm Android хуже refpipe более чем на 1 п.п.
```
Чанкинг ASR (`ChunkPlanner`) — порт `refpipe/vad.py::make_chunks`: слияние интервалов VAD в чанки <= `asr.chunk.max_s`,
рез только в паузах, а слишком длинный интервал режется по самому тихому 20-мс кадру (раньше резали на равные части —
посреди слов). Слишком короткий чанк дополняется тишиной до `chunk.min_s`; для NeMo-transducer `feature_dim = 64`.
`audio.normalize_gain` (peak) — `AudioNormalizer` (порт `bench_asr/audio_norm.py`), применяется после декодирования.

## Модули

| Модуль | Тип | Содержимое |
|---|---|---|
| `:core` | Kotlin/JVM (чистый, без Android) | типы и интерфейсы §4, `Aligner` (§5), `TextGuard` (§6), `TranscriptJson` (§1 + расширенный), `TierConfig`, `PipelineOrchestrator`, `ResourcePlanner`, `ModelStore`, `Resampler`/`WavDecoder`/`WavWriter`, `ChunkPlanner`/`TokenWords`, `LlmPunctuator`, экспортёры SRT/VTT/TXT/JSON |
| `:audio` | Android library | `MediaCodecAudioDecoder` (MediaExtractor+MediaCodec → моно → 16 кГц), `AudioRecorder` (AudioRecord 16 кГц mono → WAV) |
| `:engine-sherpa` | Android library | адаптеры sherpa-onnx: `SherpaVad`, `SherpaAsr`, `SherpaDiarizer`, `SherpaLanguageId`, `SherpaDenoiser` |
| `:engine-llm` | Android library + CMake | JNI-обёртка llama.cpp (`llm_jni.cpp`), `LlamaAssistant : LlmAssistant` |
| `:app` | Android application | flavors `lite`/`medium`/`hard`/`online`, Compose-заглушка, `ProcessingService` (foreground), `ModelBootstrap` |

Зависимости: `:app → :audio, :engine-sherpa, :engine-llm → :core`. `:core` ни от кого не зависит.

### `:core` — что где

* `Model.kt`, `Engines.kt` — типы и интерфейсы §4 (+ `AudioDecoder`, `Denoiser`, `LanguageId`, `AudioInput`).
* `Aligner.kt` — порт `src/eval/refpipe/refpipe/align.py` 1:1 (целочисленная арифметика, тай-брейки). Проверяется
  `AlignCasesTest` на `src/eval/refpipe/tests/align_cases.json` (23 кейса) и `GuardCasesTest` на `guard_cases.json` (22).
* `TextGuard.kt` — `norm`, guard, fallback, `GuardedPunctuator` (слова/времена не меняются, сбой LLM не падает).
* `TranscriptJson.kt` — вручную сериализует ТЗ-JSON (порядок ключей, целые, UTF-8 без `\u`, перенумерация
  `speaker_N`, сортировка, зажим времён в `[0, duration]`); `toFullJson` — расширенный (слова, confidence, meta, RTF).
* `TierConfig.kt` — `kotlinx.serialization`, `ignoreUnknownKeys`, дефолты; `requiredModelPaths()`; `language`,
  `asr_en`, `word_tail_ms`, `diarization.enabled` и др.
* `PipelineOrchestrator.kt` — `decode → (denoise) → vad → (lid) → [asr ∥ diarization] → align → punctuation → export`.
  Движки создаются и закрываются самим оркестратором по очереди (`EngineProviders`): ASR и диаризация выгружаются до
  создания LLM. Время каждой стадии в `meta.stageMs`, прогресс монотонный, отмена через корутины (движки получают
  `ProgressListener`, бросающий `CancellationException`, + `runInterruptible`). Деградация вместо падения: сбой denoise/VAD/LID/
  диаризации/пунктуации → предупреждение и запасной путь; фатальны только decode и ASR (`PipelineException` с названием стадии).
* `ResourcePlanner.kt` — чистая функция «память + конфиг → план» (потоки, LLM-пунктуация, второй проход, денойз).
  Android передаёт цифры из `ActivityManager.MemoryInfo`.
* `models/ModelStore.kt` — `models/<set-id>/` + `manifest.json`, атомарная установка (`.tmp-<id>` → rename), проверка
  размеров/sha256, активный набор, `delete` и повторная `install` («удалить встроенный набор и восстановить из assets»).
* `audio/` — `Resampler`/`ResamplerStream` (полифазный windowed-sinc, окно Кайзера β=8, ~−80 дБ; идентичен при любом
  разбиении на блоки), `Pcm`, `WavWriter`, `WavDecoder` (PCM 8/16/24/32, float, extensible; запасной путь декодера).
* `asr/AsrSupport.kt` — `ChunkPlanner` (чанки по паузам VAD), `TokenWords` (порт `build_words` эталона: слова и время
  из токенов), `AsrModelFiles` (раскладка файлов моделей sherpa), `SpanOps`.
* `llm/LlmPunctuator.kt` — пунктуация пачками «N. текст» (ChatML/Qwen) + guard + бюджет времени `time_budget_rtf`.

Фейковые движки лежат только в `core/src/test` (`FakeEngines.kt`).

### Варианты сборки (`:app`)

`flavorDimensions = ["tier"]`: `lite`, `medium`, `hard`, `online`; `applicationId = ru.neurosteno.app.<tier>`,
имя «Нейро-стенографист Lite/Medium/Hard/Online» (`resValue`), `BuildConfig.TIER` / `BUNDLED_MODELS`.
`online` — без встроенных моделей (менеджер загрузки — волна 2); использует `medium.json` как конфиг по умолчанию.

* Конфиг: Gradle-задача `copy<Variant>TierConfig` кладёт `config/tiers/<tier>.json` в `assets/tier.json`.
* Модели: `app/src/<tier>/assets/models/<tier>/…` наполняет `scripts/stage_models.sh <tier>` (пути берёт из конфига;
  размеры и sha256 пишет в `manifest.json`). При первом запуске `ModelBootstrap` копирует набор в
  `filesDir/models/<tier>` (sherpa-onnx и llama.cpp требуют путь к файлу); без моделей приложение не падает, а показывает
  сообщение.
* `noCompress`: `onnx, gguf, bin, txt`; `abiFilters = arm64-v8a`; minSdk 26, targetSdk 35, compileSdk 36.
* Подпись release: `scripts/build_all.sh` генерирует ключ в `$ROOT/.local/keystore` (в git не попадает).
* Разрешения: `INTERNET`/`ACCESS_NETWORK_STATE` — только ради будущего менеджера моделей, `RECORD_AUDIO`, foreground
  service (`dataSync`), `POST_NOTIFICATIONS`, `WAKE_LOCK`. Модули обработки сетевого кода не содержат — это проверяет
  `ArchitectureGuardTest` (исходники и зависимости `:core`, `:audio`, `:engine-*`; белый список разрешений манифеста).

## Тесты и проверки

```bash
cd src/android && ./gradlew :core:test        # 176 тестов JUnit 5 (в т.ч. parity с Python-эталоном)
```

Без Android SDK дополнительно проверено на JVM хоста (macOS arm64) **с реальными моделями** — `tools/jvm-smoke`:

```bash
source scripts/env.sh
src/android/scripts/fetch_sherpa_onnx.sh --host-native
src/android/scripts/build_host_jni.sh                  # JNI-обёртка llama.cpp под хост (cmake/ninja из .local/bin)
SMOKE_LLM_GGUF=$ROOT/.cache/models/llm-qwen3-0.6b-q4_k_m/Qwen3-0.6B-Q4_K_M.gguf \
  src/android/scripts/jvm_smoke.sh $ROOT/.cache/data/ru-dialog-tts-cats/audio/cats_c02_v0_call_phone.wav medium
```

Это те же классы, что работают на Android (`SherpaVad/Asr/Diarizer`, `LlamaAssistant`, оркестратор), только вместо
`MediaCodecAudioDecoder` используется `WavDecoder`.

## Решения и ограничения

* `:core` содержит `Resampler`, `WavWriter`, `WavDecoder`, `ModelStore` (в задании часть из них — в `:audio`): чтобы
  тестировать на JVM без SDK; `:audio` даёт `typealias WavWriter`.
* Kotlin API sherpa-onnx вызывает callback диаризации по сигнатуре `invoke(IIJ)I` — её генерирует только класс-лямбда,
  поэтому в `:engine-sherpa` включён `-Xlambdas=class` (иначе «Failed to get the callback»).
* Нативный `process` диаризации sherpa-onnx не прерывается: отмена во время диаризации ждёт конца вызова.
* sherpa-onnx не отдаёт confidence → `Word.confidence == null`, второй проход (`asr.second_pass`) пока не реализован.
* GPU/NNAPI — только опционально и с откатом на CPU: `LlamaAssistant` пробует `gpuLayers > 0` и при неудаче грузит на
  CPU, в сборке llama.cpp GPU-бэкенды выключены, `PipelineRunner` передаёт `gpuLayers = 0`. ARM-архитектура ggml —
  `armv8.2-a+dotprod` (`-Pneurosteno.llm.arm_arch=…`); без `asimddp` в `/proc/cpuinfo` `LlamaAssistant.availability()`
  отключает LLM (процесс не падает на SIGILL).
* R8 отключён (JNI и сериализация), размер APK определяют модели.
* Android-модули (`:audio`, `:engine-*`, `:app`) написаны и проходят конфигурацию AGP 9.5.1 до этапа получения SDK
  (проверено), но **не собирались** — нужны SDK-пакеты выше.
