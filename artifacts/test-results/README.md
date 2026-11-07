# Артефакты тестирования

| Что | Где |
|---|---|
| `results.csv` | по строке на (прогон × файл); колонки фиксированы, пишет `stenoeval score --csv` |
| `REPORT.md` | сводные таблицы; **генерируется** командой ниже, руками не править (TODO(финал): создать после финальных прогонов) |
| `ui-smoke/` | JSON из дымовых прогонов на эмуляторе (до исправления B28; не показатель качества) |
| `device/` | выгрузки `process_on_device.sh` (результат, `.full.json`, маркер `.done.json`) — TODO(финал) |

```bash
cd src/eval/stenoeval
.venv/bin/stenoeval report ../../../artifacts/test-results/results.csv --out ../../../artifacts/test-results/REPORT.md
```
Методика и сводка — `docs/TEST_RESULTS.md`.
