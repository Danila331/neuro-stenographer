# Методика и результаты тестирования

## Методика

- **Инструмент:** `src/eval/stenoeval` (CLI `stenoeval`, 77 тестов). Определения метрик — `src/eval/stenoeval/README.md`.
- **WER:** `(S+D+I)/N` по словам, спикеры игнорируются, микро-усреднение. Режимы: `norm` (без знаков, регистр и е/ё не важны),
  `punct` (знаки и регистр учитываются, е=ё; знак приклеен к слову), `p-sep` (знак — отдельный токен), `num` (числа словами).
  Методика жюри неизвестна (B27), поэтому считаем несколько режимов; главные — `norm` и `punct`.
- **cpWER:** реплики склеиваются по спикерам, спикеры сопоставляются венгерским алгоритмом (штраф за ошибку в диаризации).
- **DER:** `pyannote.metrics`, перекрытия учитываются; сегменты — реплики целиком (как у жюри: паузы внутри реплики — речь);
  основной вариант `DER ±.25` (NIST collar ±0,25 с), `DER` без collar — рядом.
- **Метки времени:** среднее |Δ| начала/конца реплики в мс по выравниванию слов и доля реплик с |Δstart| < 300 мс.
- **RTF** = время обработки / длительность записи. На ПК относительный (общая загруженная машина); на устройствах — измерен.
- **Данные:** 70 % dev (подбор параметров) / 30 % test (не трогаем при подборе). Наборы — `src/eval/datasets/README.md`.

## Как обновить итоговые таблицы

Все прогоны пишутся в `artifacts/test-results/results.csv` (по строке на прогон и файл). Таблицы строит одна команда:

```bash
cd src/eval/stenoeval
.venv/bin/stenoeval report ../../../artifacts/test-results/results.csv --out ../../../artifacts/test-results/REPORT.md
#   --run-ids a,b   только эти прогоны;   --summary-only   только сводка
```
Новый прогон: `refpipe run …` (ПК) или `src/android/scripts/process_on_device.sh …` (устройство), затем
`stenoeval score --dataset <корень> --run <папка> --split test --csv ../../../artifacts/test-results/results.csv`
(`--platform android --device jury-4g` для устройства). Сравнение: `stenoeval compare … --run-ids базовый,новый`.
Отчёт пишется в `REPORT.md`, а не в этот файл, чтобы не затирать текст. Правила артефактов — `artifacts/test-results/README.md`.

## Наборы данных

| Набор | Файлов | Что |
|---|---|---|
| ru-asr-dev | 700 (1,33 ч) | одиночные русские фразы: FLEURS, VoxForge, Golos (farfield), RuLS; чистые и искажённые |
| ru-dialog-synth | 36 (1,73 ч) | диалоги 2–4 реальных дикторов; условия clean/noise/reverb/overlap/phone/farfield; сценарии «встреча» и «звонок» |
| ru-dialog-tts-cats | 60 (0,70 ч) | «котики»: заседание, протокол; 2–4 голоса TTS |
| en-asr-dev / en-dialog-synth | 260 / 14 | английские фразы и диалоги (LibriSpeech, FLEURS) |
| ru-en-mixed | 5 | смесь языков по репликам |
| real | 0 | реальные записи (B25): TODO(финал) — не размечены |

## Результаты: ASR (компоненты, ПК)

Mac M3 Pro, sherpa-onnx, int8, 4 потока, test-части, WER %. Полные таблицы (dev, все модели, режимы):
`src/eval/bench_asr/results/final/SUMMARY_ru.md`. Выбранная модель — GigaAM v3 RNNT punct (обоснование: `docs/MODELS.md`).

| Модель | ru-asr-dev norm | synth norm | cats norm | synth p-sep | cats p-sep |
|---|---|---|---|---|---|
| **GigaAM v3 RNNT punct** | 7,18 | 16,77 | 22,96 | 30,83 | 32,54 |
| GigaAM v3 RNNT | 5,63 | 17,57 | 20,08 | 45,86 | 54,65 |
| GigaAM v3 CTC punct | 7,39 | 17,46 | 25,09 | 33,18 | 40,14 |
| Zipformer-ru int8 | 9,58 | 23,74 | 26,85 | 49,66 | 58,99 |

Здесь `norm` = `norm_num` (числа словами), `p-sep` = `pc_sep_num`; метрики те же, что в stenoeval (`bench_asr` использует его нормализатор).
Другие замеры: громкость — `final/normalize_gain.md`; чанки — `results/chunk_*.jsonl`; определение языка — `final/lid_*.jsonl`;
память — `final/mem.jsonl` (GigaAM v3 RNNT punct: 483 МБ пик RSS при чанке 25 с).

## Результаты: сквозной пайплайн (WER, cpWER, DER, метки, RTF)

TODO(финал): таблицы из `REPORT.md` для сдаваемой конфигурации (medium) на **test** — `synth`, `cats`, `en`, по условиям
(clean/noise/reverb/overlap/phone/farfield), ПК-эталон и Android. Образец вывода (заполнить цифрами команды `stenoeval report`):

| Прогон | Платформа | Файлов | WER norm | WER punct | cpWER | DER ±.25 | start MAE, мс | RTF |
|---|---|---|---|---|---|---|---|---|
| TODO final-medium-synth-test | ПК (refpipe) | | | | | | | |
| TODO final-medium-cats-test | ПК (refpipe) | | | | | | | |
| TODO final-medium-jury4g | эмулятор jury-4g | | | | | | | |
| TODO final-medium-pixel10 | Pixel 10 | | | | | | | |

Диаризация (DER, число говорящих) — финальные цифры после diar-bench: TODO(финал).
Промежуточные прогоны refpipe (подбор VAD и громкости) в `results.csv` не являются итогом и в отчёт не входят.

## Паритет Android = эталон (B28)

Обработка на телефоне — тот же пайплайн и конфиг, что в Python-эталоне. Проверка: `src/android/scripts/parity.sh <набор> <N> medium`
(падает, если WER norm Android хуже на > 1 п.п.). Результат: расхождение 0,00 п.п. (BACKLOG B28), проверено на текущей модели
(GigaAM v3 RNNT-punct, `asr_native`, B30; сообщение архитектора 2026-11-07). До исправления чанкинга на чистой записи `cats_c01`
Android давал WER 40 % против единиц процентов на ПК (резка речи равными кусками).
TODO(финал): повторить `parity.sh` на финальной конфигурации и приложить `summary.txt`.

## Устройства

| Устройство | Условия | Результат | Источник |
|---|---|---|---|
| jury-4g (ARM64, 4 ГБ, 4 ядра, без GPU), medium debug, без LLM, B30 | `cats_c01` | WER norm 12,84 %, punct 27,52 %, RTF 0,54, пик PSS 490 МБ | сообщение архитектора 2026-11-07 ~03:20 |
| То же | `cats_c04_call_overlap` | WER norm 25,53 %, punct 48,94 %, RTF 0,57 | там же |
| То же | `synth_0004` | WER norm 8,54 %, punct 26,17 %, RTF 0,50, пик PSS 514 МБ | там же |
| jury-4g, ранний замер (до LID по фрагментам) | основной путь | RTF ≈ 0,33; с тех пор вырос до 0,5–0,57 из-за LID по каждому фрагменту | `docs/ARCHITECTURE.md` |
| jury-4g, ранний замер | с LLM-пунктуацией (отвергнуто) | пик PSS 2,1 ГБ, RTF 0,75; на 2 ГБ RAM RTF 3,9 | `docs/ARCHITECTURE.md` |
| jury-4g | протокол на Qwen3-1.7B (после обработки) | пик PSS ≈ 2,2 ГБ | замер android-ui |
| Pixel 10, **старая сборка** (CTC без знаков) | короткие записи | RTF 0,16–0,23, без падений и ANR | `docs/BACKLOG.md` B36 |
| Pixel 10, старая сборка | диаризация 882 → 2524 мс при длине ×2,1 | растёт быстрее длины записи | B35 |

TODO(финал): повторить на итоговых APK, включая Pixel 10 на актуальной модели (`process_on_device.sh`: RTF, пик PSS, потоки — в маркере `<имя>.done.json`), все 6 эталонных записей.

## Прочие проверки

JVM-тесты `:core` (176) и `:app` (66), 3 UI-теста на эмуляторе, `ArchitectureGuardTest` (в модулях обработки нет сетевого кода),
дымовые прогоны через интент (`artifacts/test-results/ui-smoke/`; выгрузки до исправления B28 — не показатель качества).
Числа тестов — по README `src/android` и `src/android/app`; TODO(финал): прогнать и приложить вывод.

## Оговорки

- Наборы синтетические/из чтения; на реальных диалогах цифры будут хуже (`docs/LIMITATIONS.md`).
- Методика жюри не подтверждена (B27); числа цифрами против словами меняют WER ≈ на 5,5 п.п. (B29).
- ПК-RTF — относительный; абсолютные замеры только на устройствах.
- По правилам команды test-части не использовались при подборе параметров (пороги подбирались по dev); dev-значения — в исходных таблицах.
