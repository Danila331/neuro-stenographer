# refpipe — Python-эталон пайплайна «Нейро-стенографист»

Тот же пайплайн, что в Android-приложении (ARCHITECTURE §4), на **тех же моделях и том же конфиге
`config/tiers/<tier>.json`**: sherpa-onnx (Silero VAD, GigaAM, pyannote-3.0 + эмбеддинги + кластеризация),
Aligner §5, guard §6. На нём подбираются параметры на ПК; Android проверяется на совпадение.

```
decode (soundfile/ffmpeg, mono, soxr 16 kHz) -> [denoise GTCRN если SNR < порога] -> Silero VAD
   ├─ pad/merge -> чанки <= asr.chunk.max_s (резка только в паузах) -> ASR -> слова с абсолютным временем
   └─ диаризация (pyannote seg + embedding + fast clustering) -> turns          (два потока)
-> Aligner (слова x turns -> реплики) -> Punctuator (none | asr_native | llm + guard) -> <id>.json
```

## Установка

```bash
cd src/eval/refpipe
uv venv --python 3.11 .venv
uv pip install --python .venv/bin/python -e ".[dev]"        # numpy, scipy, soundfile, soxr, sherpa-onnx
uv pip install --python .venv/bin/python -e ".[llm]"        # (опц.) llama-cpp-python для punctuation.source=llm
.venv/bin/pytest -q                                          # 70 тестов (e2e пропускается, если нет моделей)
```
mp3/ogg/opus/flac/wav читает libsndfile (soundfile). m4a/aac/mp4 — через `ffmpeg` (или `afconvert` на macOS);
если их нет — понятная ошибка с подсказкой.

## Модели

```bash
python scripts/fetch_models.py --list                      # таблица: каталог -> URL -> лицензия
python scripts/fetch_models.py --tier medium --no-llm      # -> $ROOT/.cache/models (атомарно, sha256 в .sha256)
python scripts/fetch_models.py --tier all --candidates     # + кандидаты для бенчей (GigaAM CTC v3, punct-варианты, эмбеддинги)
```
Только официальные источники (GitHub релизы k2-fsa/sherpa-onnx, HF `csukuangfj/*` и `ggml-org/*`), без токенов.
Скрипту нужен только stdlib. Каталоги совпадают с путями в `config/tiers/*.json`.

## Запуск

```bash
refpipe run --config config/tiers/medium.json --models $ROOT/.cache/models \
            --input some.wav --out /tmp/run1 --override punctuation.source=none
refpipe run --config config/tiers/medium.json --input $ROOT/.cache/data/<dataset> --split dev --out /tmp/run2 \
            --override diarization.clustering.threshold=0.7 --override vad.threshold=0.3 [--oracle-num-speakers]
```
`--input`: файл, папка с аудио или корень датасета (`manifest.csv`, §2). `--num-speakers N` форсирует число
говорящих; `--oracle-num-speakers` берёт его из manifest; `--num-threads`; `--language auto|ru|en`; `--cache DIR` (кэш ASR/диаризации: перебор `align.*`/`punctuation.*` без пересчёта
моделей; в meta — `cached_stages`, время такого прогона не годится для RTF); `--override a.b.c=json` (повторяется;
`null`, числа, строки). Без `--models` берётся общий кэш `.cache/models` главного репозитория.

Выход (`<run_dir>/`):
* `<id>.json` — строго §1 (кириллица без `\u`, компактный JSON);
* `<id>.meta.json` — `processing_ms`, `stage_ms{decode,denoise,vad,asr,diarization,asr_diar_wall,align,punctuation}`,
  `model_load_ms`, `peak_rss_mb` (пик RSS дерева процессов за файл, вместе с загруженными моделями), `rtf`, `tier`, `config_hash` (sha256 канонизированного конфига *после* override, без ключей `_*`),
  `models`, `num_threads`, `device`, `platform`, `info` (SNR, число отброшенных guard'ом ответов LLM ...);
* `<id>.full.json` — расширенный: реплики со словами (`text,start_ms,end_ms,confidence`), `turns`, `speech`, `chunks`
  (`stenoeval` берёт из него пословные времена);
* `config.json` — фактический конфиг прогона.

Оценка: `stenoeval score --dataset <root> --run <run_dir>` (пакет eval-harness).

## Как устроены стадии (детали, важные для порта на Kotlin)

* **decode** — mean по каналам; soxr `VHQ` в 16 кГц; клип в [-1, 1].
* **denoise** (если `audio.denoise.enabled`) — `estimate_snr_db`: кадры 30 мс, мощность; шум = среднее по 10 % самых тихих,
  сигнал = среднее по 50 % самых громких; SNR < `snr_threshold_db` -> GTCRN (`OfflineSpeechDenoiser`), длина выхода
  приводится к длине входа.
* **VAD** — `SileroVadModelConfig(threshold, min_silence, min_speech, window=512, max_speech)`; окно подаётся по 512
  сэмплов, в конце `flush()`. Затем `pad_ms` с обеих сторон и склейка пересекающихся интервалов.
* **LanguageId** (`lid.py`) — `language.mode`: `auto` (Whisper-LID по первым `language.lid_seconds`≈10 с речи;
  `en` -> `asr_en`, всё остальное -> `ru`), `ru`, `en`. Без `language.lid_model` или `asr_en` — всегда `ru`. `asr_en` — та же
  структура, что `asr` (недостающие поля берутся из дефолтов).
* **Чанки ASR** — жадно: добавляем следующий интервал, пока `last.end - first.start <= chunk.max_s`; чанк — непрерывный
  срез аудио (паузы внутри остаются). Слишком длинный интервал режется по самому тихому 20-мс кадру во второй половине
  допустимого окна. Чанк короче `chunk.min_s` дополняется нулями. `overlap_ms` пока не поддерживается (0).
* **ASR** (`asr.py`) — `engine`: `gigaam_rnnt` (NeMo transducer, `model_type=nemo_transducer`), `gigaam_ctc`
  (`from_nemo_ctc`), `transducer` (zipformer и т. п.), `whisper` (без меток токенов). Файлы модели ищутся по соглашению
  (`encoder.int8.onnx`, `decoder.onnx`, `joiner.onnx`, `model.int8.onnx`, `tokens.txt`). `asr.batch_size` (по умолчанию 1 =
  как на Android) — чанков за один `decode_streams`.
* **Слова и время** — sherpa отдаёт только *начало* каждого токена (сетка 40 мс), длительностей у GigaAM нет. GigaAM-токены
  — символы и пробел. `word.start` = время первого символа; `word.end` = время последнего символа + `word_tail_ms`
  (40 мс = кадр энкодера), но не позже начала следующего слова; абсолютное время = `chunk.start + round(ts*1000)`;
  confidence = среднее `exp(logp)` токенов слова. Для Whisper (меток нет) слова раскладываются по чанку пропорционально
  длине. Доп. параметры (`asr.word_tail_ms`, `asr.word_start_offset_ms`, `asr.batch_size`, `asr.language`,
  `diarization.num_threads`, `diarization.enabled`) — расширения refpipe, отсутствующие в `config/tiers`.
* **Второй проход** (`asr.second_pass`, hard) — Whisper перераспознаёт чанки со средней уверенностью слов ниже порога;
  слова сопоставляются с первым проходом дифом (`second_pass.py`), времена одинаковых слов сохраняются.
* **Диаризация** — `OfflineSpeakerDiarization`; число говорящих: `--num-speakers` > `clustering.num_speakers` > порог
  `clustering.threshold`; если кластеров больше `max_speakers`, повторная кластеризация с `num_clusters=max_speakers`.
  ASR и диаризация работают параллельно (по `num_threads` на каждую). Диаризация вынесена в **подпроцесс**: биндинг
  `OfflineSpeakerDiarization.process` держит GIL (проверено), и в потоке она сериализовала бы ASR. Доп. параметр
  `diarization.window_shift_ratio` (шаг окна pyannote/окно, по умолчанию 0.1; 0.5 примерно в 3-5 раз быстрее).
* **Aligner** (`align.py`) — строго §5, все неоднозначности зафиксированы в docstring; кейсы `tests/align_cases.json`
  (слова + turns + параметры -> ожидаемые реплики, 23 случая) — их же должен проходить Kotlin-порт.
* **Punctuator** (`punct.py`) — `none` (слова lower без пунктуации), `asr_native` (слова с пунктуацией от
  *punct*-моделей GigaAM v3 — их надо положить в `asr.model_dir`), `llm` (llama-cpp-python; промпты — зона llm-bench; если
  нет библиотеки/модели — предупреждение и откат на `none`). `norm`/`guard_text` — §6, кейсы `tests/guard_cases.json`.
* **Экспорт** — реплики с `text` непустым, `end_ms` обрезается длительностью файла.

## Тесты

```bash
.venv/bin/pytest -q          # align_cases.json, guard_cases.json, слова/время, чанкинг, конфиг, e2e на sample (если есть модели)
```
