#!/usr/bin/env python3
"""Генерирует src/test/resources/llm_cases.json -- примеры для проверки порта 1:1 (ProtocolMerge, PromptBuilder) против эталона
`src/eval/bench_llm/bench_llm/android_flow.py`: ожидаемые значения считает САМ Python-код эталона.

    python3 src/android/app/tools/gen_llm_cases.py            # перегенерировать после правки эталона

Зависит только от стандартной библиотеки (модуль requests, нужный эталону для запуска сервера, подменяется заглушкой).
"""
import json
import sys
import types
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
sys.modules.setdefault("requests", types.ModuleType("requests"))
sys.path.insert(0, str(REPO / "src/eval/bench_llm"))
from bench_llm import android_flow as af  # noqa: E402
from bench_llm.text import norm  # noqa: E402

P1 = """Резюме: Обсудили бюджет на квартал и перенос релиза.
Вопросы:
- Бюджет проекта [00:10]
- Перенос релиза на пятницу [01:05]
Решения:
- Релиз переносим на пятницу [01:20]
Поручения:
- Мурзик: подготовить отчёт до среды [01:40]
Цитаты:
- «Релиз переносим на пятницу» (Говорящий 1, [01:20])
- «Это совершенно выдуманная цитата» (Говорящий 2, [01:30])"""

P2 = """Резюме: Распределили дежурства по складу.
Вопросы:
- Дежурство по складу [03:15]
- Бюджет проекта [03:30]
Решения:
- нет
Поручения:
- Говорящий 2: взять ночное дежурство [03:50]
Цитаты:
- «Я поменяю смену» (Говорящий 2, [03:48])"""

P3 = """Резюме: Короткое совещание.
Вопросы
- Тема один
Решения:
Поручения: нет
Цитаты:
"""

P4 = "Резюме Обсудили ёлки\nВопросы:\n  -  Ёлка во дворе [00:01]\n- ёлка во дворе   [00:01]\nРешения:\n- Купили Ёлку\n\n\nПоручения:\n---- Нет\n- нет\nЦитаты:\n- «Ёлка стоит во дворе» (Говорящий 1)\r\n- простой текст без цитаты\r\n"

TRANSCRIPT = [
    "Релиз переносим на пятницу, коллеги.",
    "Я поменяю смену!",
    "Ёлка стоит во дворе у ворот",
]

LINES = [f"[00:{i:02d}] Говорящий {i % 2 + 1}: " + ("слово " * (3 + i % 7)).strip() for i in range(1, 41)]

cases = {
    "split": [{"text": t, "expected": af.split_sections(t)} for t in (P1, P2, P3, P4, "", "Вопросы:\n- один\nмусор до заголовка")],
    "merge": [{"parts": parts, "expected": af.merge_protocols(parts)} for parts in (
        [P1, P2], [P2, P1], [P1], [P3, P4], [P1, P1, P2, P3, P4], ["", P2], ["Резюме: А", "Резюме: ", "Резюме: Б"],
    )],
    "filter": [{"text": t, "transcript": TRANSCRIPT, "expected": af.filter_quotes(t, norm(" ".join(TRANSCRIPT)))} for t in (P1, P2, P4, "")]
    + [{"text": af.merge_protocols([P1, P2]), "transcript": TRANSCRIPT, "expected": af.filter_quotes(af.merge_protocols([P1, P2]), norm(" ".join(TRANSCRIPT)))}],
    "budget": [{"n_ctx": n, "max_new": m, "system_chars": s, "expected": af.content_budget(n, m, s)}
               for n, m, s in ((4096, 450, 1400), (2048, 450, 1400), (2048, 700, 300), (256, 700, 2000), (4096, 400, 400))],
    "chunk": [{"lines": LINES, "budget": b, "expected": af.chunk(LINES, b)} for b in (120, 400, 1500, 100000)]
    + [{"lines": ["x" * 500, "коротко"], "budget": 100, "expected": af.chunk(["x" * 500, "коротко"], 100)}],
    "select": [{"chunks": af.chunk(LINES, 400), "question": q, "budget": b, "expected": af.select_relevant(af.chunk(LINES, 400), q, b)}
               for q, b in (("слово слово", 800), ("Кто говорил слово?", 400), ("вопрос", 100000), ("zzzz", 500))],
}
out = HERE.parent / "src/test/resources/llm_cases.json"
out.write_text(json.dumps(cases, ensure_ascii=False, indent=1), encoding="utf-8")
print(f"{out}: " + ", ".join(f"{k}={len(v)}" for k, v in cases.items()))
