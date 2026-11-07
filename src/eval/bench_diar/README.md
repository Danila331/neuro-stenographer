# bench_diar — подбор и улучшение диаризации

Что здесь: метрики (DER/JER), прогон штатного sherpa-onnx, поэтапная реплика его конвейера с кэшем,
перебор эмбеддингов/порогов и **эталонная реализация нового алгоритма «говорящие по ASR-фразам»**
(`bench_diar/spkphrase.py`) — это спецификация для порта в Kotlin.

## Установка и тесты
```bash
cd src/eval/bench_diar
uv venv --python 3.11 .venv
uv pip install --python .venv/bin/python -r pyproject.toml
uv pip install --python .venv/bin/python -e ../stenoeval -e ../refpipe
./fetch_models.sh                     # сегментация + эмбеддинги -> $ROOT/.cache/models/diar-emb-<имя>/model.onnx
PYTHONPATH=. .venv/bin/python -m pytest -q tests      # DER vs pyannote.metrics, ward vs scipy, юниты
```

## Главная метрика (BACKLOG B22)
`bench_diar/e2e.py`: слова из кэша ASR прогона refpipe (`.cache/runs/refpipe-medium-rescue`, dev; для test —
`.cache/runs/diar-bench-test-asr`, получен `refpipe run --override diarization.enabled=false`) × turns → **Aligner
refpipe** → итоговые реплики → `stenoeval` (DER collar 0 и ±0.25 с (NIST), JER, число говорящих, cpWER norm). Меняются
только turns, поэтому ASR не пересчитывается.

```bash
# итоговый сравнительный прогон (base = штатный sherpa как в конфиге medium; phrase; hybrid)
PYTHONPATH=. .venv/bin/python -m bench_diar.final_eval --split dev  --methods base,phrase,hybrid --emb <имя> --turn-emb <имя>
PYTHONPATH=. .venv/bin/python -m bench_diar.final_eval --split test --methods base,phrase,hybrid --emb <имя> --turn-emb <имя>
```

## Модули
| файл | что |
|---|---|
| `metrics.py` | свой кадровый DER/JER (проверен на pyannote.metrics) — для быстрых перебором |
| `runner.py`, `sweep.py` | штатный `OfflineSpeakerDiarization` с кэшем, сетки порогов/shift |
| `pipeline.py`, `stage.py`, `stage2.py`, `tune.py` | поэтапная реплика конвейера sherpa (A сегментация, B эмбеддинги (чанк, спикер), C кластеризация, D сборка turns). Результат **бит-в-бит как у штатного** на проверенных файлах; перебор порогов без пересчёта |
| `e2e.py`, `exp.py`, `final_eval.py` | сквозная оценка на итоговых репликах |
| `phrase.py`, `count.py`, `variants.py`, `improve.py` | прототипы (юниты по паузам, ward/AHC, silhouette, eigengap, recluster) |
| `spkphrase.py` | **эталон нового алгоритма** (numpy, без scipy), `tests/test_spkphrase.py` |
| `synth.py`, `fetch_pool.py` | собственный генератор диалогов (не использован: нет сети) |

## Алгоритм «говорящие по ASR-фразам» (`spkphrase.py`, порт в Kotlin 1:1)
Вход: слова ASR (мс), turns штатного диаризатора (необязательно — только как точки смены говорящего), аудио 16 кГц,
функция эмбеддинга (sherpa `SpeakerEmbeddingExtractor`, в `:core` — `Diarizer.speakerEmbeddings(clips)`).
Выход: turns `(label, start_ms, end_ms)`, которые подставляются вместо turns диаризатора перед Aligner (§5).

1. **Юниты.** Слова (по порядку) группируются, пока пауза между соседними словами `<= gap_ms` и длина юнита `<= max_unit_ms`.
   Затем каждый юнит режется между соседними словами, у которых «доминирующий turn» разный (turn с максимальным
   пересечением со словом; первый при равенстве), если обе части `>= split_min_side_ms`.
2. **Эмбеддинг юнита** = extractor(аудио `[start-pad_ms, end+pad_ms]`) (юнит длиннее `embed_max_s` — центральное окно);
   L2-нормировка. Юнит короче 0.3 с дополняется нулями.
3. **Надёжные юниты** — длительность `>= min_reliable_ms` (если их < 3 — все); если их больше `max_cluster_units`, берутся самые длинные.
4. **Кластеризация** надёжных: иерархическая, ward (Lance–Williams, квадраты евклидовых расстояний на единичной сфере).
5. **Число говорящих** k ∈ [`k_min`, min(`k_max`, n−1)] — максимум средней силуэтной меры (расстояние 1−cos); при равенстве меньший k.
6. **Метки**: центроиды (сумма эмбеддингов × длительность юнита, L2-норма); КАЖДЫЙ юнит (и короткие) получает центроид с
   наибольшим скалярным произведением; затем `refine` раундов пересчёта центроидов по всем юнитам.
7. turns = `(метка, юнит.start, юнит.end)`.

Сложность: эмбеддингов столько, сколько юнитов (≈ 1 на фразу; суммарно ≈ 1× длины записи), ward O(n³) при n ≤ 400.
Если диаризатор не нашёл говорящих («No speakers found») или `turns = null`, шаг разрезания пропускается — алгоритм
работает и без него.

Параметры по умолчанию (`Params`): `gap_ms=300` (в рекомендации — 200), `max_unit_ms=12000`, `split_min_side_ms=300`,
`pad_ms=40`, `embed_max_s=10`, `min_reliable_ms=800`, `k_min=2`, `k_max=6`, `max_cluster_units=400`, `refine=1`.
