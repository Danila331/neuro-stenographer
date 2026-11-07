# Артефакты тестирования

| Что | Где |
|---|---|
| `results.csv` | по строке на (прогон × файл); колонки фиксированы, пишет `stenoeval score --csv` |
| `REPORT.md` | сводные таблицы; **генерируется** командой ниже, руками не править |
| `ui-smoke/` | JSON из дымовых прогонов на эмуляторе (до исправления B28; не показатель качества) |
| `prerelease2/` | финальная регрессия release-APK (16/16 PASS): `README.md`, выгрузки 6 эталонов (результат, `.full.json`, маркер `.done.json`), 30-минутная запись, lite на 2 ГБ |
| `ui-summary-search/`, `ux-timeline-pdf/`, `live-transcribe/`, `ui-fragment/`, `llm-integration/` | проверки отдельных функций |

```bash
cd src/eval/stenoeval
.venv/bin/stenoeval report ../../../artifacts/test-results/results.csv --out ../../../artifacts/test-results/REPORT.md
```
Методика и сводка — `docs/TEST_RESULTS.md`.
