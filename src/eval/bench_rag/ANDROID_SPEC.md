# ANDROID_SPEC — B50 «Выжимка» и B51 «AI-поиск» (порт из Python-эталона `bench_rag`)

Все классы — в `:core` (чистый Kotlin/JVM), эталон — одноимённый файл в `bench_rag/bench_rag/`; паритет: те же входы → те же чанки, ранги, GBNF, отбрасывания (JSON-примеры из `tests/`).
Исключения вне `:core`: `OrtTextEmbedder`, `ObjectBoxVectorStore` (`:app`), вызов llama.cpp (`LlmAssistant` уже есть).

## 1. Когда что считается
| Шаг | Когда | Что |
|---|---|---|
| Индексация записи | сразу после сохранения JSON, фон (WorkManager, не в основном пути) | чанки → термы BM25 (в БД) → эмбеддинги (embedder загрузить → посчитать → выгрузить) → `VectorStore`. ~20 мс/чанк на телефоне (оценка), 30-мин запись ≈ 10 с |
| Выжимка | по кнопке «Выжимка» (tier hard — можно автоматически в фоне); результат `summary.json` рядом с записью | LLM грузится после выгрузки embedder/ASR; несколько минут на 30-мин запись (см. README) |
| Поиск | при вводе вопроса | BM25 (в памяти) + вектор запроса (embedder ≈ 10 мс) + RRF; ответ LLM — по кнопке «Ответить» |
| Переименование говорящих | без переиндексации | имена — метаданные записи, разбор в `SpeakerQueryParser` в момент запроса |
| Повторная обработка фрагмента / удаление записи | сразу | `deleteRecord(recordId)` + индексация заново |
Embedder, LLM и ASR никогда не в памяти одновременно (бюджет 1.5 ГБ).

## 2. Классы `:core` (`ru.neurosteno.core.rag`, `.summary`)
| Класс | Что делает | Эталон |
|---|---|---|
| `RuStemmer` | Snowball Russian + `snow5` (основа ≤ 5 символов), ё→е, стоп-слова, `QUERY_STOP` (слова вопроса) | `text.py` |
| `Chunker(mode)` | `Chunk(id, recordId, uttIdx[], speakers[], startMs, endMs, text, embedText)`. Режимы: `turn` (склейка соседних фраз одного говорящего при паузе ≤ 1.5 с и ≤ 400 симв.), `win3s1` (3 хода, шаг 1; embedText с «Говорящий N:») — **по умолчанию `win3s1`** | `chunker.py` |
| `Bm25Index` | k1 1.2, b 0.75, idf `ln(1+(N-n+.5)/(n+.5))`; строится в памяти из сохранённых термов (10⁵ чанков ≈ 30 МБ) | `bm25.py` |
| `SpeakerQueryParser` | `speaker_N`, «говорящий 2», «второй спикер», имена (по первой лексеме основы) → `SpeakerQuery(clean, numbers, nameStems)`; `matches(sq, speakerId, names)` | `speaker_filter.py` |
| `HybridSearcher` | RRF (k 60, вес 0.5/0.5, нулевой BM25 не голосует); фильтр говорящего — **soft** (+0.15 диапазона оценок), hard только для списка «все реплики говорящего»; область: запись или вся история | `retrieval.py` |
| `TextEmbedder`, `VectorStore` | интерфейсы (ниже); `BruteForceVectorStore` (int8 + переранжирование f32) — в `:core`, для тестов и tier lite | `vecdb_bench.py` |
| `AnswerBuilder` | контекст ≤ 3200 симв. (группы по записям, по времени), `verify()` — ссылки `[мм:сс, Говорящий N]` вне контекста удаляются | `answer.py` |
| `TopicSegmenter` | долины косинуса соседних окон по векторам реплик: k 3, α 0, мин. 3 реплики и 300 симв., максимум `fragment_chars` | `segment.py` |
| `SummaryPlanner` / `SummaryGrammar` / `SummaryValidator` / `SummaryMerger` / `SummaryJson` | фрагменты → GBNF (подстановка номеров и говорящих) → разбор и проверка ссылок → склейка соседних тем → JSON для UI | `summary.py` |

```kotlin
interface TextEmbedder : AutoCloseable { val dim: Int; fun embed(texts: List<String>, kind: Kind): List<FloatArray> /* L2-нормированные */ ; enum class Kind { QUERY, DOC } }
interface VectorStore { fun upsert(recordId: String, chunkIds: List<Long>, vectors: List<FloatArray>); fun deleteRecord(recordId: String)
                        fun search(q: FloatArray, k: Int, recordId: String? = null): List<Pair<Long, Float>> }   // косинус
data class Citation(val recordId: String, val startMs: Long, val speakerId: String)   // из ответа: «[мм:сс, Говорящий N]»
```

## 3. Схема выжимки (`summary.json`, UI рисует её без разбора текста)
```json
{"overview": "1–2 предложения (LLM, по заголовкам и решениям; пусто, если выключено)",
 "topics": [{"title": "Мыши на складе", "start_ms": 52000, "end_ms": 118000,
   "points":    [{"speaker": "speaker_2", "text": "Насчитали 47 мышей", "refs": [{"n": 21, "t": 67200, "speaker": "speaker_2"}]}],
   "decisions": [{"text": "Дежурной назначили Багиру", "refs": [{"n": 37, "t": 99500, "speaker": "speaker_1"}]}],
   "tasks":     [{"who": "Говорящий 2", "text": "составить отчёт к утру", "refs": [{"n": 40, "t": 104000, "speaker": "speaker_2"}]}]}]}
```
`n` — номер реплики (с 1) в расшифровке, `t` — её `start_ms`: **время всегда берётся из реплики, не из текста модели**. Нажатие на ссылку → прокрутка к реплике. Правила проверки (`SummaryValidator`): ссылка вне фрагмента — удалить; пункт говорящего K со ссылкой на чужую реплику — переназначить на лучшую реплику K или удалить; пункт, чьи основы слов (стеммер) покрыты цитируемой репликой < 0.3 (решения/задачи < 0.5), — удалить; пустая тема — удалить. Тема: `start/end` = границы фрагмента.

## 4. `config/tiers/<tier>.json` — новые блоки (верхний уровень; `llm.summary` остаётся флагом «Протокол»)
| Поле | lite | medium | hard |
|---|---|---|---|
| `summary.mode` | `extractive` (без LLM) | `llm` | `llm` |
| `summary.fragment_chars` / `max_new_tokens` / `n_ctx` | 1500 / – / – | 1500 / 700 / 4096 | 1500 / 700 / 4096 |
| `summary.min_support_point` / `min_support_item` / `merge_title_sim` | – | 0.3 / 0.5 / 0.45 | 0.3 / 0.5 / 0.45 |
| `summary.segmenter` {`k`,`alpha`,`min_utts`,`min_chars`} | 3 / 0.0 / 3 / 300 | то же | то же |
| `summary.overview`, `summary.auto` | false, false | true, false | true, true |
| `search.enabled` | true | true | true |
| `search.embedder.model` / `tokenizer` | `embed/rubert-tiny-turbo/model.int8.onnx` / `…/tokenizer.json` | `embed/user2-small/model.int8.onnx` / `…/tokenizer.json` | то же, что medium |
| `search.embedder` {`dim`,`pooling`,`query_prefix`,`doc_prefix`,`max_len`,`threads`} | 312, `cls`, «», «», 256, 2 | 384, `mean`, `search_query: `, `search_document: `, 256, 2 | то же |
| `search.chunk` / `search.bm25` / `search.fusion` | `win3s1` / {1.2, 0.75, `snow5`} / {`rrf`, 60, 0.5} | то же | то же |
| `search.speaker_filter`, `search.top_k` | `soft`, 5 | `soft`, 5 | `soft`, 5 |
| `search.store` | `bruteforce` | `objectbox` (HNSW, cosine, 384) | `objectbox` |
| `search.answer` {`enabled`,`top_chunks`,`max_context_chars`,`max_new_tokens`} | false (только список реплик) | true, 3, 3200, 160 | true, 3, 3200, 160 |
Модель ответа/выжимки — `llm.model` tier'а (0.6B для этих задач не годится, см. README).

## 5. `scripts/fetch_models.py` — записи (официальные URL, sha256 с Hugging Face)
| `dir` | Файлы (dest ← URL, размер, sha256) | Лицензия | Tier |
|---|---|---|---|
| `embed/user2-small` | `model.int8.onnx` — **собирается** `python -m bench_rag.export_onnx user2-small` из `deepvk/USER2-small` (`model.safetensors` 137 572 816 Б, sha256 `f4fb765b…0529`), итог 33 МиБ; `tokenizer.json` ← `https://huggingface.co/deepvk/USER2-small/resolve/main/tokenizer.json` (4 753 524 Б) | Apache-2.0 | medium, hard |
| `embed/rubert-tiny-turbo` | `model.int8.onnx` — собирается так же из `sergeyzh/rubert-tiny-turbo` (`model.safetensors` 116 781 184 Б, sha256 `92ef8906…d107`), 27 МиБ; `tokenizer.json` (2 413 691 Б) | MIT | lite |
| `embed/e5-small` (запасной, без конверсии) | `model.int8.onnx` ← `https://huggingface.co/intfloat/multilingual-e5-small/resolve/main/onnx/model_qint8_avx512_vnni.onnx` (118 346 824 Б, sha256 `dd476dd0…2d88`); `tokenizer.json` ← `…/onnx/tokenizer.json` (17 082 730 Б, sha256 `0b44a9d7…4c39`) | MIT | запасной |
| `embed/qwen3-embedding-0.6b` (кандидат hard) | `Qwen3-Embedding-0.6B-Q8_0.gguf` ← `https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/main/Qwen3-Embedding-0.6B-Q8_0.gguf` (639 150 592 Б, sha256 `06507c7b…c439`) | Apache-2.0 | – |
Готовый код записей (с полными sha256) — `fetch_models_entries.py.txt`; готовые `model.int8.onnx` уже лежат в общем кэше `.cache/models/embed/<name>/`. Конверсия — один раз на сборщике (`pip install -e ".[export]"` в `src/eval/bench_rag`, ~1 мин); Android-проект получает готовый `model.int8.onnx` + `tokenizer.json`. Токенайзеры для порта: USER2 — byte-level BPE (NFC), rubert-tiny — WordPiece (`BertNormalizer`, без lowercase), e5 — Unigram + `Precompiled` charsmap (самый трудный для порта).

## 6. Хранение и БД
* Room: `records`, `chunks(id, recordId, uttFrom, uttTo, startMs, endMs, speakers, text, terms)`, `summaries(recordId, json, model, createdAt)`. `terms` — основы через пробел (BM25 строится без повторной токенизации).
* Векторы — `ObjectBoxVectorStore`: сущность `ChunkVec(id = chunkId, recordId, @HnswIndex(dimensions = 384, distanceType = COSINE) vec: FloatArray)`; запрос `nearestNeighbors(q, k)` + фильтр по `recordId`. Обоснование и риски (бинарная лицензия, 16 КБ) — README §«Векторная БД». Запасной путь — `BruteForceVectorStore` (интерфейс тот же).
* Промпты: `prompts/android/summary_system.txt`, `summary_overview_system.txt`, `search_answer_system.txt`; GBNF: `summary.gbnf.tmpl` (+ пример `summary.gbnf.example`). Сэмплинг greedy, ChatML без «размышлений» (как `PromptBuilder.chatml`); флаги llama.cpp — как в `llm.*` tier'а (`-nr`, KV q8_0, flash-attn); префикс системного промпта держать в KV-кэше между вызовами (иначе ×1.6 к времени, README).
