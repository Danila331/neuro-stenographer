# Архитектура решения

> Документ — источник истины для всех агентов. Меняет его только архитектор (тимлид).
> Если контракт мешает — не обходите его, а опишите проблему в отчёте.

## Контекст

Android-приложение «Нейро-стенографист»: полностью офлайн превращает запись разговора в
структурированную расшифровку с говорящими и временными метками, использует локальную LLM,
показывает результат и экспортирует JSON строго по ТЗ.

Оценка жюри: WER 40, диаризация 25, временные метки 10, скорость (RTF) 10, UX 10, защита 5.
Отсюда приоритеты: **точность ASR > диаризация > стабильность > скорость > фичи**.

Жюри ставит APK, импортирует 3 записи «диалогов котиков», запускает обработку, экспортирует JSON.
Следствия:
- **модели лежат внутри APK** (никаких импортов пакетов и скачиваний);
- обработка не требует ни одного действия, кроме «выбрать файл → обработать → экспорт»;
- приложение не падает ни на одном входе.

Ограничения от жюри (docs/BACKLOG.md): проверка автоматическая, на **ARM** (эмулятор и, возможно,
реальные устройства), ориентир — **4 ГБ RAM**; язык — **русский в первую очередь, английский во
вторую**; сценарии — и быстрые звонки, и встречи на диктофон.

## Компоненты

```
Uri (SAF) / микрофон
  → AudioDecoder        MediaExtractor+MediaCodec → mono → 16 kHz float PCM (anti-aliased resample)
  → [Denoiser]          опционально, только если оценка SNR < порога
  → Vad                 Silero VAD → речевые интервалы
  → LanguageId          ru | en по первым ~10 с речи (или ручной выбор) → выбор ASR-модели
  ├→ AsrEngine          по чанкам (границы чанков — паузы VAD) → слова с абсолютным временем
  └→ Diarizer           pyannote-segmentation-3.0 + speaker embedding + кластеризация → turns
  → Aligner             слова × turns → реплики (чистый Kotlin, детерминированный)
  → Punctuator          источник: asr_native | punct_model | llm (+ guard неизменности слов)
  → TranscriptExporter  JSON по ТЗ (+ расширенный JSON, TXT, SRT, VTT)
  → [LlmAssistant]      «Протокол заседания», вопросы по расшифровке — после показа результата,
                        не входит во время основной обработки
```

ASR и диаризация независимы и выполняются **параллельно** (два потока, делят ядра).

### Варианты сборки (product flavors)

Один код, три набора моделей. Различаются только `assets/models/**` и `config/tiers/<tier>.json`.

| | lite | medium | hard |
|---|---|---|---|
| цель | минимальный RTF, слабые устройства | основной кандидат для сдачи | максимум точности на флагмане |
| пиковая RAM | ≤ 1.0 ГБ | **≤ 1.5 ГБ** (AVD 4 ГБ) | ≤ 4 ГБ, только ≥ 8 ГБ, на автопроверку не идёт |
| APK | ≤ 0.8 ГБ | ≤ 1.6 ГБ | ≤ 3.5 ГБ |

**Бюджет памяти:** модели загружаются по очереди: ASR и диаризация выгружаются до запуска LLM.
При старте проверяем доступную RAM (`ActivityManager.MemoryInfo`); если её мало, снижаем нагрузку
(меньше потоков, без LLM-пунктуации) — но никогда не падаем.

**Языки:** основная ASR — русская; английская ASR-модель есть в lite/medium/hard (выбор по
замерам, в т.ч. многоязычная модель вместо двух). В конфиге tier: `language.mode`
(`auto` | `ru` | `en`), `language.lid_model`, `asr_en` (та же структура, что `asr`). Если язык
определить не удалось, используется `ru`.

Конкретные модели выбираются **по замерам** (см. `docs/TEST_RESULTS.md`), стартовые кандидаты —
в `config/tiers/*.json`. applicationId: `ru.neurosteno.app.<tier>` (можно ставить все три рядом).

Четвёртый вариант — **`online`**: без встроенных моделей, при первом запуске скачивает выбранный
набор (lite/medium/hard). Это второй APK по ответу жюри (BACKLOG Q2) — для UX, не для автопроверки.

### Менеджер моделей (все варианты; BACKLOG Q2, B5)

Решение жюри: APK для автопроверки обязан содержать встроенную модель, а загрузка моделей из
сети засчитывается в плюс по UX. Делаем оба предложенных жюри варианта:
1. варианты lite/medium/hard содержат встроенный набор, на котором основной путь работает сразу
   и в режиме полёта; вариант `online` — второй APK без встроенных моделей;
2. в каждом варианте есть экран «Модели»: скачать другой набор (прогресс, докачка, отмена,
   проверка sha256, проверка свободного места), выбрать активный, **удалить встроенный набор**
   (после копирования из assets освобождается место в `filesDir`; сами assets остаются
   в APK — честно пишем это в UI) и восстановить его из assets.

Набор моделей = каталог `models/<set-id>/` + `manifest.json` (id, tier, файлы с sha256 и
размерами, версия конфига). Источник загрузки — официальные URL моделей (релизы sherpa-onnx,
Hugging Face) из `config/models.json`; своего сервера нет. Загрузка — WorkManager, только по
действию пользователя; основной путь обработки никогда не ждёт сети.

## Контракты

### 1. Выходной JSON (ТЗ, неизменяемо)

Схема: `config/transcript.schema.json`.

```json
{"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"Добрый день!"}]}
```

Правила (проверяются валидатором в `src/eval` и unit-тестом в Android):
- ровно один ключ верхнего уровня `transcript`; у реплики ровно 4 ключа;
- `speaker_id` = `speaker_N`, N с 1, нумерация по порядку первого появления;
- `start_ms`, `end_ms` — целые, `0 ≤ start_ms < end_ms ≤ длительность`;
- `text` непустой, без ведущих/хвостовых пробелов; UTF-8, кириллица без `\u`-экранирования;
- сортировка по `start_ms`, при равенстве — по `end_ms`; пересечения реплик допустимы;
- нет речи → `{"transcript":[]}`.

Расширенный экспорт (`*.full.json`) — отдельный файл: слова с временем и confidence, версия
приложения, tier, модели, время по стадиям, RTF.

### 2. Формат eval-датасета

```
<dataset_root>/
  manifest.csv            id,audio,ref,duration_ms,num_speakers,lang,source,condition,split
  audio/<id>.wav          16 kHz mono PCM16 (оригиналы других форматов — audio_orig/)
  ref/<id>.json           тот же формат, что выход (transcript)
```
`condition`: clean | noise | reverb | overlap | phone | real. `split`: dev | test (test не трогаем
при подборе параметров).

Гипотезы: `<run_dir>/<id>.json` (формат выхода) + `<run_dir>/<id>.meta.json`
(`{"processing_ms":..., "stage_ms":{...}, "tier":..., "config_hash":..., "device":...}`).

### 3. Конфиг варианта (`config/tiers/<tier>.json`)

Один и тот же файл читают **и Android, и Python-эталон** — это гарантия, что параметры,
подобранные на ПК, переносятся на телефон. Пути моделей — относительно корня моделей
(Android: `assets/models/`, ПК: `.cache/models/`). Поля: см. `config/tiers/medium.json`.
Неизвестные поля игнорируются (forward-compatible), отсутствующие — берут дефолт из кода.

### 4. Kotlin-интерфейсы (модуль `:core`, чистый Kotlin/JVM, без Android-зависимостей)

```kotlin
package ru.neurosteno.core

/** Моно PCM, float в [-1, 1]. */
class AudioBuffer(val samples: FloatArray, val sampleRate: Int = 16_000) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate
}
data class TimeSpan(val startMs: Long, val endMs: Long)
data class Word(val text: String, val startMs: Long, val endMs: Long, val confidence: Float? = null)
data class SpeakerTurn(val speaker: Int, val startMs: Long, val endMs: Long)
data class Utterance(
    val speakerId: String,          // "speaker_1"
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<Word>,
)
data class Transcript(val utterances: List<Utterance>, val meta: TranscriptMeta)
data class TranscriptMeta(
    val tier: String, val audioDurationMs: Long, val processingMs: Long,
    val stageMs: Map<String, Long>, val models: Map<String, String>, val appVersion: String,
)

enum class Stage { DECODE, DENOISE, VAD, ASR, DIARIZATION, ALIGN, PUNCTUATION, EXPORT }
fun interface ProgressListener { fun onProgress(stage: Stage, fraction: Float) }

interface Vad : AutoCloseable { fun detect(audio: AudioBuffer): List<TimeSpan> }
interface AsrEngine : AutoCloseable {
    /** Возвращает слова с АБСОЛЮТНЫМ временем (от начала файла). */
    fun transcribe(audio: AudioBuffer, speech: List<TimeSpan>, progress: ProgressListener): List<Word>
}
interface Diarizer : AutoCloseable {
    /** numSpeakers == null → автоопределение. Turns могут пересекаться. */
    fun diarize(audio: AudioBuffer, numSpeakers: Int?, progress: ProgressListener): List<SpeakerTurn>
}
interface Punctuator : AutoCloseable {
    /** Только пунктуация/регистр; последовательность нормализованных слов обязана сохраниться. */
    fun punctuate(utterances: List<Utterance>, progress: ProgressListener): List<Utterance>
}
interface LlmAssistant : AutoCloseable {
    fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit = {}): String
}
```
Отмена — через корутины (`ensureActive()` между чанками); движки обязаны проверять отмену
не реже раза в ~1 с работы.

### 5. Алгоритм выравнивания (Aligner) — эталон в Python, порт в Kotlin 1:1

1. Каждое слово получает спикера turn'а с максимальным пересечением по времени
   `[word.start, word.end]`; если пересечения нет — ближайший turn по расстоянию до середины слова;
   если turns нет вообще — `speaker 0`.
2. Сглаживание: одиночное слово (или ≤ `align.min_island_words`) другого спикера между словами
   одного спикера, короче `align.min_island_ms`, переназначается соседям — только если его
   собственное пересечение с turn'ом < 50 % длины слова (защищает короткие «да»/«угу»).
3. Последовательные слова одного спикера склеиваются в реплику; новая реплика начинается при
   смене спикера или паузе > `align.max_gap_ms`.
4. `start_ms` = начало первого слова, `end_ms` = конец последнего (не границы VAD/turn).
5. Перенумерация спикеров в `speaker_1..N` по первому появлению; спикеры без слов исчезают.
6. Текст реплики = слова через пробел (пунктуацию добавляет Punctuator позже).

### 6. Guard для Punctuator/LLM

`norm(s)` = lower → `ё→е` → удалить всё, кроме букв/цифр/пробелов → схлопнуть пробелы.
Если `norm(output).split() != norm(input).split()` для реплики — берём input (плюс заглавная
первая буква и точка в конце). Слова никогда не теряются и не добавляются.

## Основные технические решения

- **Рантайм ASR/VAD/диаризации:** sherpa-onnx (Kotlin API, prebuilt AAR, ONNX Runtime, int8).
- **LLM:** llama.cpp (JNI, GGUF, CPU; GPU OpenCL/Vulkan — эксперимент для hard).
- **UI:** Jetpack Compose, Material 3; обработка в Foreground Service; хранение — файлы + Room.
- **Только arm64-v8a.** minSdk 26, targetSdk 35.
- **Модели в assets без сжатия** (`noCompress`), при первом запуске копируются в `filesDir`
  с прогрессом (llama.cpp и часть sherpa-onnx требуют путь к файлу).
- **Python-эталон** (`src/eval/refpipe`) повторяет пайплайн на тех же моделях и том же
  конфиге — на нём подбираются параметры (быстро, на M3), Android проверяется на совпадение.

## Зависимости

См. `config/models.json` (модели: источник, версия, sha256, лицензия) и `docs/TEAM.md`.

## Безопасность и приватность

Разрешение INTERNET есть **только ради менеджера моделей**. Модули обработки (`:core`, `:audio`,
`:engine-*`) не содержат сетевого кода — это проверяется тестом (в их зависимостях нет сетевых
библиотек, в коде нет `java.net`/`okhttp`). Инструментальный тест прогоняет обработку при
отключённой сети. В UI видна пометка «Обработка выполняется на устройстве». Аудио и
расшифровки хранятся только в приватной директории приложения; экспорт — только по явному
действию пользователя через SAF (и автосохранение для автопроверки, BACKLOG B3).
