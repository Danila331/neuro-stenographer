# bench_asr — бенчмарк ASR-кандидатов (sherpa-onnx) для Нейро-стенографиста

Статус: **готово**. Итоговые таблицы — `results/final/*.md` (SUMMARY_ru.md, normalize_gain.md, prosody.md, second_pass_rudev.md, mem.jsonl и др.),
сырые гипотезы — `results/raw/<tag>/`. Выводы и рекомендации по tier — в финальном отчёте агента (docs/TEST_RESULTS.md ведёт архитектор).

## Установка
```bash
cd src/eval/bench_asr
uv venv --python 3.11 .venv
uv pip install --python .venv/bin/python -e . num2words
scripts/fetch_models.sh [имена архивов]     # k2-fsa/sherpa-onnx release asr-models -> $ROOT/.cache/models (атомарно, возобновляемо)
```
Данные: `$ROOT/.cache/data/{ru-asr-dev,ru-dialog-synth,ru-dialog-tts-cats}` (агент datasets), временные: `asr-bench-tmp` (FLEURS ru dev, 200 фраз без цифр),
`asr-bench-en-tmp` (FLEURS en dev, 200), `asr-bench-long` (4 записи ~2 мин из фраз FLEURS с границами реплик), `asr-bench-cond-*` (pink10/babble10/phone/reverb),
`asr-bench-short-tmp` (80 коротких реплик Piper). Скрипты сборки: `scripts/prep_fleurs_tmp.py`, `build_long.py`, `make_conditions.py`, `make_short_replies.py`.

## Скрипты (python -m bench_asr.<имя>)
| модуль | что |
|---|---|
| `run_one` | модель x потоки x датасет -> WER raw/norm/norm_num, RTF (wall и cpu), пик RSS, размер, jsonl по файлам |
| `run_matrix` | оркестратор `run_one` (acc: T=4 весь набор; speed: T=1 и T=4, повторы) |
| `report` | `results/raw/<tag>/` -> `results/matrix_<tag>.{csv,md}` и `bycond_<tag>.md` |
| `run_chunking` | длина чанка 10/20/30/seg/whole на длинных записях: WER, RTF, RAM, ошибка границ реплик (метки токенов -> слова) |
| `run_robust` | галлюцинации на тишине/шумах/синтетическом мяуканье (raw и через VAD) |
| `run_short` | «да/угу/ага/мяу», потери на VAD |
| `run_hotwords` | modified_beam_search + hotwords (L1/L2, scores) на диалогах котиков и общих фразах |
| `run_lid` | определение языка ru/en (whisper-tiny/base/small в sherpa-onnx) |
| `run_mem` | пик RSS модели в чистом процессе (загрузка -> чанк 10 с -> чанк 25 с) |
| `second_pass` | оракул/эвристика/ROVER по готовым гипотезам |

Нормализация WER — из `src/eval/stenoeval/textnorm.py` (raw / norm / norm_num), чтобы цифры совпадали с харнессом.
Очередь: `scripts/queue_runner.sh` читает `jobs/jobs.txt` (по одной команде, строго последовательно).

## Условия замеров
Машина общая (M3 Pro, load average 80–140 при 12 ядрах): **RTF/время в таблицах нельзя использовать как абсолют**, только как грубый порядок;
точность (WER) от нагрузки не зависит. `peak_rss_mb` в `matrix_*` включает загруженный в память набор (~130 МБ) — для бюджета памяти используй `run_mem`.

## Что запускалось (воспроизведение)
```bash
D=$ROOT/.cache/data; M=gigaam3_ctc,gigaam3_rnnt,gigaam3_ctc_punct,gigaam3_rnnt_punct,gigaam2_ctc,zip_ru_2504_int8
python -m bench_asr.run_matrix --dataset $D/ru-asr-dev --tag full_rudev --models $M --phases acc --acc-threads 6
python -m bench_asr.run_matrix --dataset $D/ru-dialog-synth --tag full_synth --models $M --phases acc --extra=--utterances   # реплики по эталонным меткам
python -m bench_asr.report_full --tag full_rudev --by source,condition --split dev --metric pc_sep_num --refstyle punct
python -m bench_asr.make_final                      # сводка ru (norm_num, norm, pc_sep_num, pc_num; dev/test)
python -m bench_asr.report_norm                     # нормализация громкости (audio.normalize_gain)
python -m bench_asr.eval_prosody                    # просодическая пунктуация, пороги по dev
python -m bench_asr.run_chunking --model gigaam3_ctc --dataset $D/ru-dialog-tts-cats --split dev --modes 10,20,25,30,seg
python -m bench_asr.run_hotwords --model gigaam3_rnnt --cats $D/ru-dialog-tts-cats --general $D/asr-bench-tmp --out results/hotwords.jsonl
python -m bench_asr.second_pass --tag full_rudev --primary gigaam3_rnnt --secondary whisper_turbo_int8 --others gigaam3_ctc,zip_ru_2504_int8
python -m bench_asr.run_mem --model gigaam3_ctc --threads 4 --out results/final/mem.jsonl   # пик RSS в чистом процессе
```
Метрики со знаками (ответ жюри Q13/Q14): `pc` (знак — часть слова), `pc_sep` (знаки отдельными токенами), суффикс `_num` — цифры словами; регистр сохраняется, ё->е.
VAD-чанки: `bench_asr/vad.py` — сегменты Silero не склеиваются, длинные режутся по самому тихому месту (энергия) в последних 45% окна max_s.
