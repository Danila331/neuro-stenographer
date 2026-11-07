# :app — интерфейс «Нейро-стенографиста» (Jetpack Compose, нео-брутализм поверх Material 3)

Один экран-Activity (`MainActivity`) + `AppRoot` с простым стеком навигации (`nav/Nav.kt`, стек переживает поворот).
Обработка идёт в foreground-сервисе (`ProcessingService`), интерфейс только наблюдает `ProcessingState`.
Путь жюри не изменился: **выбрать файл → обработка стартует сама → расшифровка → «Экспорт JSON»**, без онбординга,
диалогов и разрешений до импорта. Те же стабильные подписи и `resource-id` (testTag) для автоматизации:

| Элемент | Подпись | resource-id |
|---|---|---|
| Импорт | «Выбрать аудиофайл» | `btn_pick_audio` |
| Запись | «Записать» | `btn_record` |
| Отмена обработки | «Отменить» | `btn_cancel_processing` |
| Экспорт по ТЗ | «Экспорт JSON» | `btn_export_json` |

Также работают `ACTION_VIEW` / `ACTION_SEND` с `audio/*` (обработка стартует сразу) и автосохранение
`Android/data/<pkg>/files/results/<имя>.json` (+ `.full.json`; после правок в интерфейсе файлы перезаписываются).

### Маркер завершения (B32, B33)
Рядом с результатом приложение пишет **`<имя>.done.json`** — последним и атомарно: если он есть, `.json` и `.full.json` уже на диске.
В нём: `status`, `rtf`, `processing_ms`, `audio_duration_ms`, `stage_ms`, `utterances`, `language`, `tier`, `model_set`,
`peak_pss_mb`, `threads`, `device_model`, `android_sdk`, `total_ram_mb`, `app_version`, `results_dir`, `files`, `problems`, `warnings`.
При сбое — `<имя>.error.json` (`status=error`, `error`). Те же `peak_pss_mb`/`threads`/`device_model`/`android_sdk` — в `meta` расширенного
JSON и в строке logcat `done <имя>: … peak_pss_mb=… threads=… device=… results=…` (тег `Neurosteno`). Скрипты ждут маркер, а не logcat.
Запросы LLM пишут в logcat `llm_done kind=protocol|qa …` и атомарный `<имя>.llm.json` в ту же папку `results`;
маркер содержит модель, число сгенерированных токенов, время, измеренный выборкой пик PSS и статус. `prompt_tokens` — оценка по размеру UTF-8,
так как используемый интерфейс llama.cpp не отдаёт токены промпта; в маркере это отмечено полем `prompt_tokens_estimated`.
Если `results` недоступна для записи (остаток от другой установки/uid → `EACCES`), берётся `results-<uid>`, затем внутренняя `filesDir/results`;
путь — в `results=` строки `done` и в `results_dir` маркера.

## Дизайн-система (B52, `ui/theme`)

Нео-брутализм по `docs/DESIGN_BRIEF.md`: белый фон и чёрный текст (тёмная тема -- инверсия), рамки 2-3 dp, жёсткие тени без размытия,
радиус 3 dp, один неоновый акцент, 8 цветов говорящих с чёрным текстом.

- `Accent.kt` -- акцент. **Один переключатель**: `AccentConfig.DEFAULT` (`GREEN` #B6FF00 или `PINK` #FF2E88). В debug-сборке акцент
  меняется на лету: `adb shell am broadcast -a ru.neurosteno.app.DEBUG_ACCENT -p ru.neurosteno.app.medium --es accent pink` (green|pink|default).
  Контраст чёрного текста на акценте (WCAG): зелёный 17,3:1, розовый 6,0:1; сам акцент как цвет текста не используется (на белом 1,2:1 / 3,5:1).
- `Color.kt` -- `BrutalPalette`, `SpeakerPalette` (один и тот же цвет говорящего на всех экранах, через `speakerFill(id)`).
- `Type.kt` -- шрифты OFL с кириллицей, вшиты в APK (`res/font`, лицензии в `assets/licenses`): Unbounded (заголовки), Golos Text (текст),
  JetBrains Mono (время и числа). Все три -- вариативные, начертание задаётся `FontVariation`.
- `Theme.kt` -- `NeuroTheme`, `Brutal` (bg, ink, accent, muted...), размеры `BrutalDims`, форма `BrutalShape`.
- `Primitives.kt` -- `BrutalCard`/`BrutalSurface`, `BrutalButton` (нажатая «вдавливается» в тень), `BrutalIconButton`, `BrutalChip`, `SpeakerChip`,
  `BrutalTag`, `BrutalPlaque`, `BrutalSwitch`, `BrutalProgress`, `BrutalSlider`, `BrutalTextField`, `SectionTitle`. Тень рисуется в зарезервированном
  отступе справа/снизу, поэтому родители (LazyColumn, Scaffold) её не обрезают. `BrutalButton` по умолчанию -- по ширине содержимого;
  `fillMaxWidth()`/`weight()` вызывающего растягивают и лицо кнопки.
- Резерв под «Выжимку» (B50) и «AI-поиск» (B51): неактивные плитки «СКОРО» на экране расшифровки (`slot_summary`, `slot_ai_search`).
- Скрипты скриншотов: `app/tools/redesign_shots.sh` (4 экрана x светлая/тёмная x оба акцента) и `app/tools/redesign_extra_shots.sh` (остальные экраны, шрифт x1,6, альбомная).

## Живая расшифровка при записи (B13)

Во время записи с микрофона экран «Запись» показывает текст по мере речи (превью). Итог -- обычная полная обработка после «Остановить»
(точный текст, знаки, говорящие); живой текст только набросок и заменяется ею.

- `:core` `live/`: `StreamingAsr` (интерфейс движка), `LivePreview` (накопление фраз), `LiveSession` (рабочий поток и очередь: поток
  записи не блокируется, при отставании старый звук отбрасывается; модель создаётся и выгружается внутри сеанса), `StreamingModelFiles`.
  Тесты на фейке: `LivePreviewTest`, `LiveSessionTest`, `StreamingModelFilesTest`.
- `:engine-sherpa` `SherpaStreamingAsr`: `OnlineRecognizer` (zipformer-transducer, int8), endpoint detection по `live.endpoint.*`.
- `:audio`: `AudioRecorder` отдаёт каждую порцию 100 мс в `onPcm`; источник звука -- `PcmSource` (микрофон; в debug -- WAV вместо микрофона).
- `:app` `live/LiveController`: `ResourcePlanner.planLive` (RAM, ядра, наличие модели) -> включать ли превью; модель живёт только между началом
  и концом записи и выгружается ДО полной обработки. Нет памяти/модели -- экран пишет причину, запись идёт как раньше.
- Конфиг `config/tiers/*.json`, блок `live` (`enabled`, `model_dir` = `asr/streaming-zipformer-small-ru-int8`, потоки, пороги памяти, endpoint).
  Модель необязательна: `requiredModelPaths()` её не включает, наборы без неё остаются полными (превью выключено).
- Отладка без микрофона (эмулятор): `app/tools/live_check.sh <wav> <метка> [сек]` подаёт WAV в «Запись» в реальном времени
  (`adb shell am broadcast -a ru.neurosteno.app.DEBUG_MIC_WAV --es path <wav>`, только debug), снимает экран и собирает метрики из лога (`live:`).
- Подготовка набора для проверки до обновления `fetch_models.py`:
  `scripts/stage_models.sh medium --map asr/streaming-zipformer-small-ru-int8=sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16`.

## Дорожка говорящих и экспорт в PDF (B54)

- **Дорожка** (`ui/components/SpeakerTimeline`, раскладка `:core` `timeline/TimelineLayout`): полоса во всю ширину под плеером; отрезки реплик цветами
  говорящих (те же, что у меток), перекрывающиеся реплики -- на соседних дорожках (до 3), вертикальная метка позиции, тап и протяжка -- перемотка
  (при протяжке метка идёт за пальцем, перемотка -- при отпускании). Реплики, добавленные повторной обработкой (`TranscriptSession.highlight`), --
  с чёрной штриховкой и толстой рамкой. Доступность: описание с позицией, `setProgress`. В альбомной ориентации и при шрифте x1,6 не ломается
  (в дорожке нет текста).
- **PDF** (`export/PdfExporter`, раскладка `:core` `pdf/PdfLayout`): пункт «PDF (расшифровка + протокол)» в листе экспорта. Нативный `PdfDocument`,
  без сети и библиотек; шрифты -- вшитые Golos Text / Unbounded / JetBrains Mono (кириллица). Содержимое: шапка (название, дата, длительность, число
  говорящих, «обработано на устройстве»), легенда говорящих с пользовательскими именами, реплики `[мм:сс] Имя: текст`, протокол LLM (только если
  уже составлен, `history/<id>/protocol.txt`), нумерация «Стр. i из N». Печатный стиль: белая бумага, чёрный текст, акцент только в полосе рамки шапки.
  Перенос по словам, слово длиннее строки режется, заголовок не остаётся один внизу страницы, у разрезанной реплики не меньше двух строк по обе стороны.
  Раскладка чистая (метрики подаются снаружи), поэтому пагинация тестируется на JVM (`PdfLayoutTest`).
- Проверка на эмуляторе: `app/tools/timeline_pdf_check.sh` (перемотка, экспорт PDF через системный диалог, хост-рендер страниц PyMuPDF через `render_pdf.py`).

## Выжимка и AI-поиск: экраны (B50/B51)

Экраны на портах `:app` `insight/` (UI не знает про движки): `SummaryRepository` (`GraphSummaryRepository` поверх `AppGraph.summary`/`SummaryRunner`;
`FakeSummaryRepository` -- тесты) и `SearchRepository` (`RealSearchRepository` поверх `SearchService`; `FakeSearchRepository` -- превью и тесты).
Модели UI -- схема `summary.json` из `:core` (`SummaryDoc`, `SummaryTopic`, ...) и `SearchModel.kt` (`SearchHit` с `HitLine`, `IndexState`, `AnswerCaps`, `AnswerEvent`).

- **«Выжимка»** (`SummaryScreen`): «О чём»; темы-карточки с диапазоном времени; пункты по говорящим (цвет и имя), решения, задачи с исполнителем;
  тап по пункту/заголовку темы -- расшифровка прокручивается к реплике на времени ссылки, плеер встаёт на него (`AppViewModel.requestFocus`).
  Метки: «быстрая выжимка · без ИИ» (extractive), «ИИ-выжимка готовится…» с прогрессом и «Остановить» (быстрая версия на экране, итоговая
  заменяет её), «ИИ-ВЫЖИМКА»; плашка «Черновик — проверьте по репликам». `summary.mode = extractive` (lite) -- пояснение «без ИИ» и выжимка
  строится сразу при открытии; ИИ недоступен (память/модель) -- быстрая версия и причина; пока на экране «Протокол» идёт генерация (LLM одна),
  запуск и «Пересоздать» отключены с сообщением. Экспорт: TXT (`SummaryExport`) и PDF (раздел «Выжимка» в `PdfLayout`; только выжимка --
  `PdfModelBuilder.buildSummaryOnly`; в общий PDF «расшифровка + протокол» выжимка входит, если она составлена).
- **«AI-поиск»** (`SearchScreen`, `SearchController`): вопрос, подсказки-примеры, «эта запись / вся история» (с главного экрана -- только история);
  сначала найденные фрагменты (говорящий, время, подсветка совпадений по основам -- `Highlight`), тап -- к реплике записи; затем «Ответить»:
  ответ ИИ со ссылками `[мм:сс, Говорящий N]`, кликабельными (`CitationParser`, запись определяется по фрагментам). Состояния: ничего не найдено,
  отказ «В записи нет ответа» (фрагменты остаются), ИИ недоступен или выключен конфигом (только фрагменты), индекс строится (прогресс из `SearchService`).
- Тесты: `insight/SummaryTest`, `insight/SearchTest`, `export/SummaryExportTest` (JVM); `InsightUiTest` (21 UI-тест на всех состояниях);
  `InsightScreenshotsTest` снимает все состояния в светлой и тёмной теме (запуск вручную: `adb shell am instrument -w -e class ru.neurosteno.app.InsightScreenshotsTest ...`);
  `app/tools/insight_check.sh` -- сквозная проверка на эмуляторе на настоящих движках (выжимка, переходы, экспорт TXT/PDF, поиск и ответ).

## Лицензии (Настройки → «Лицензии и модели»)

Экран `LicensesScreen` (модели, библиотеки, шрифты; тап -- `LicenseTextScreen` с полным текстом из `assets/licenses/`, офлайн). Реестр -- `assets/third_party_notices.json`,
его генерирует `src/android/tools/gen_notices.py` (версии из `libs.versions.toml`, поставляемые модели из `config/models.json`); сверка -- `docs/LICENSES.md`.
Тесты: `licenses/NoticesTest` (JVM: лицензия и файл текста у каждого пункта, версии, полнота моделей), `LicensesScreenTest` (Compose, на устройстве).
У CC BY 4.0 (Parakeet) и бинарной лицензии ObjectBox вложена только справка со ссылкой (`text_complete = false`).

## Экраны (`ui/screens`)

| Экран | Что есть |
|---|---|
| Главный | крупные «Выбрать аудиофайл» (SAF `OpenDocument`, `audio/*`) и «Записать»; «Обработка выполняется на устройстве»; активный набор моделей; история (дата, длительность, число говорящих, число реплик, превью, удаление); в альбомной ориентации две колонки |
| Обработка | общий %, оставшееся время (`EtaEstimator`: экстраполяция по прогрессу + RTF прошлых запусков), 7 стадий с понятными названиями, «Отменить», ненавязчивая подсказка про уведомления (без системного диалога), «подготовка моделей» при первом запуске, ошибка с понятным текстом (`FailureScreen`) |
| Расшифровка | реплики с цветной меткой говорящего и временем; плеер (play/pause, ±10 с, скорость 0,75–2×, ползунок); тап по реплике — переход и воспроизведение, текущая реплика подсвечена и автопрокручивается (ручная прокрутка отключает слежение, чип «К текущей реплике» возвращает); поиск по тексту (без регистра и ё/е, переход по совпадениям); переименование говорящих; правка текста, смена говорящего, объединение с соседней, удаление; отмена правок (snackbar и кнопка); «Экспорт JSON», «Другие форматы» (TXT, SRT, VTT, расширенный JSON, «Поделиться») |
| Повторная обработка фрагмента (B37/B45) | долгое нажатие на реплику включает выделение соседних реплик (нажатие вне диапазона расширяет, на крайнюю — сужает) либо пункт меню реплики «Обработать заново»; лист опций: язык (как в записи / авто / ru / en), подсказка сменить язык или включить «Заново определить говорящих», число говорящих (авто, 1–4; только при включённом пересчёте и больше одной реплики, для одной реплики берётся 1); запуск в `ProcessingService` (отдельное действие `REPROCESS_FRAGMENT`, прогресс и «Отменить» поверх расшифровки, уведомление); результат по `FragmentOutcome`: заменено/добавлено/удалено — сохраняется, перезаписываются `results/<имя>.json`, `.full.json` и маркер `.done.json` (с блоком `fragment`), имена говорящих переносятся по `speakerRemap`, добавленные реплики подсвечиваются, snackbar «Отменить» возвращает прежнюю расшифровку (`transcript.undo.json`); если текст и говорящие совпали, а границы отличаются не более чем на 50 мс, показывается «Без изменений» без подсветки и действия отмены. Отмена фрагмента пишет `fragment_undo` в logcat. |
| Протокол и вопросы | «Протокол заседания» и «Вопрос по расшифровке» на `LlamaAssistant` с потоковым выводом; остановка; копировать/поделиться; если LLM недоступна — причина текстом (нет файла модели, < 3 ГБ ОЗУ, нет dotprod, идёт обработка) |
| Модели | наборы (встроенный, скачанные, из каталога), активный, размер; «Скачать» (WorkManager, прогресс, скорость, докачка, отмена, sha256, проверка места), «Сделать активным», «Удалить встроенный набор», «Восстановить из APK» |
| Первый запуск `online` | единственный вариант с экраном первого запуска: выбор и загрузка набора (`OnlineSetupScreen`) |
| Настройки | язык (Авто — по фрагментам речи / Русский / English), «Числа: цифрами / словами» (`postprocess.numbers`; пока не выбрано — как в конфиге), число говорящих (Авто / 1–8; при 1 диаризация отключается), тема, сведения о наборе, очистка истории |
| Статистика обработки | время по стадиям, RTF, пик PSS процесса, сводка по запускам (BACKLOG B14) |
| Запись | таймер, индикатор уровня; разрешение микрофона запрашивается только здесь, по нажатию |

### Поведение в фоне (B34)
Уведомление обработки и загрузки обновляется не чаще 2 раз в секунду (`MinIntervalGate`). `android:enableOnBackInvokedCallback="true"`:
«Назад» на каждом экране проверен (`back_check.sh`); при открытом поиске он сначала закрывает поиск. Плеер запрашивает audio focus
(потеря → пауза, кратковременная → продолжение после возврата, ducking), ставится на паузу при `onStop` (уход в фон — Android 17 глушит
фоновый звук, лучше явная пауза), при отключении наушников и по окончании. Foreground-сервисы: `ProcessingService` и
`SystemForegroundService` WorkManager — `dataSync` (проверено по merged manifest); `onTimeout` (Android 15+) прерывает обработку, а не ждёт ANR.

Тёмная и светлая темы (`NeuroTheme`, палитра говорящих для обеих), поворот не пересоздаёт Activity (`configChanges`),
`contentDescription` у иконок и карточек, минимальные цели нажатия 48 dp, проверено при шрифте ×1,6.

## Данные

* `history/<id>/{entry.json, transcript.json, audio.<ext>, protocol.txt}` в `filesDir` (`HistoryRepository`): расшифровки,
  правки и копия аудио для плеера лежат только в приватной папке приложения.
* Правки: `edit/TranscriptEditor` (чистые функции). После каждой операции данные канонизируются (сортировка, `speaker_1..N`
  по первому появлению, имена переезжают вместе с идентификаторами) — поэтому экспортёры `:core` работают напрямую.
  Имена говорящих попадают в TXT/SRT/VTT и расширенный JSON; **JSON по ТЗ всегда `speaker_N`**.
  После правки текста слова реплики сбрасываются (в расширенном JSON `words` пуст).
* Настройки — SharedPreferences (`SettingsRepository`); язык и число говорящих переопределяют конфиг уровня в `PipelineRunner`.
* Конфиг читается по уровню активного набора (`assets/tiers/{lite,medium,hard}.json`, `manifest.tier`): скачанный набор
  другого уровня работает со своим конфигом.

## Менеджер моделей

* Каталог — `assets/model_sets.json` (генерируется `tools/gen_model_sets.py` из `scripts/fetch_models.py`: официальные URL
  GitHub/Hugging Face, размеры — реальные, по Range-запросу, sha256 — из fetch_models/stage). Файл набора: `url` либо `member`
  из архива (`archives`: так публикуется английская Parakeet, `tar.bz2`, распаковка Apache Commons Compress).
* Сетевой код — только `models/download` (`FileDownloader`: HTTPS, `.part` + `Range`, повтор; `SetInstaller`: проверка места,
  sha256, маркеры `.ok`, атомарное переименование `.dl-<id>` → `models/<id>`; `ModelDownloadWorker`: WorkManager + foreground).
* Debug-сборка: файл `Android/data/<pkg>/files/model_sets_override.json` подменяет каталог (проверка загрузки на малых файлах).
* Удаление встроенного набора запоминается (`builtinDeleted`), чтобы он не вернулся при запуске; «Восстановить из APK» — `ModelBootstrap.restoreBuiltin`.

## Проверка

```bash
cd src/android
./gradlew :core:test :app:testMediumDebugUnitTest          # JVM: 243 core + 95 app unit-тестов (редактор, экспорт, история, каталог, загрузчик на локальном HTTP-сервере, ETA, промпты)
./gradlew :app:connectedMediumDebugAndroidTest             # Compose UI-тесты на эмуляторе (5 тестов; удаляют установленное приложение)
src/android/scripts/process_on_device.sh <apk> <wav>       # холодный путь VIEW → JSON (foundation)
src/android/app/tools/screens.sh                           # скриншоты экранов в artifacts/demo/screens (uiautomator, tools/uia.py)
src/android/app/tools/back_check.sh                        # «Назад» на всех экранах (22 проверки)
src/android/app/tools/fragment_smoke.sh <apk>              # «Обработать заново»: cats_c04, имена говорящих, пересчёт, отмена, stenoeval validate
SKIP_INSTALL=1 src/android/scripts/process_on_device.sh …  # не переустанавливать APK (medium 1,6 ГБ не помещается дважды в /data эмулятора)
python3 src/android/app/tools/gen_model_sets.py            # пересобрать каталог моделей (нужна сеть)
```
`tools/uia.py` — минимальный adb+uiautomator (tap/wait/shot/type); кириллицу вводит через буфер обмена debug-сборки
(`DebugClipReceiver`, только в `src/debug`).

## LLM: протокол и вопросы (B48)

* **Флаги llama.cpp из `llm.*` конфига** (`no_repack`, `flash_attn`, `kv_cache_type`, `use_mmap`) попадают в JNI (`LlamaOptions`, `LlmConfig.toLlamaOptions()`):
  `no_repack` → `llama_model_params.use_extra_bufts = false`; `use_mmap` → `llama_model_params.load_mode = MMAP | NONE` (в этой версии llama.cpp булево
  `use_mmap` заменено перечислением `llama_load_mode`); `flash_attn` → `llama_context_params.flash_attn_type`; KV → `type_k = type_v`. Обходы: квантованный KV
  без flash attention невозможен (контекст не создаётся) — flash включается принудительно; если контекст с такими параметрами не создался, повтор с f16 и flash AUTO.
  Фактически применённые параметры — `LlamaAssistant.appliedOptions`, в строке `llm_done … llama_opts="…"` и в `<имя>.llm.json`.
  Хостовая проверка (Qwen3-1.7B, протокол одной расшифровки): пик RSS 2.71 → 1.64 ГБ, время запроса ×2–2.5 — `artifacts/test-results/llm-integration/`.
* **Протокол** `protocol_mode = chunk_merge` (`ProtocolFlow`): запись режется по бюджету контекста, на каждый фрагмент — тот же `protocol_system.txt` (до 450 токенов),
  разделы сливаются в коде (`ProtocolMerge.mergeProtocols`, порт `merge_protocols`); иной режим — прежнее «конспект → протокол». `filter_quotes` убирает цитаты, которых
  нет в расшифровке дословно. Порт 1:1 сверяется с эталоном `bench_llm/android_flow.py` по примерам, посчитанным самим Python-кодом (`tools/gen_llm_cases.py` → `src/test/resources/llm_cases.json`).
* **Вопросы:** если расшифровка не помещается в бюджет, она режется на фрагменты по `qa_chunk_budget_ratio` (0.25) от бюджета и берётся самый подходящий (≈ 1000 токенов на запрос).
* **`llm.quality = draft`** (lite, Qwen3-0.6B): над протоколом плашка «Черновой протокол — упрощённая модель», в вопросах — предупреждение.
* Промпты — ровно файлы `src/eval/bench_llm/prompts/android/` (тест `PromptAssetsTest` сверяет побайтно).

## Промпты LLM

`assets/prompts/protocol_system.txt`, `qa_system.txt`, `protocol_notes_system.txt` — финальные версии llm-bench (`src/eval/bench_llm/prompts/android/`). Длинные записи: конспект по фрагментам → протокол; вопросы — выбор релевантных фрагментов.
