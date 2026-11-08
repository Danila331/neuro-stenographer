# Hard 0.1.0 — проверка на Google Pixel 10

Дата: 2026-11-08. Устройство: Google Pixel 10, 12 ГБ RAM, Android API 37. Release-APK `neurosteno-hard-0.1.0.apk`, 3 160 397 363 байта, SHA-256 `468acd230b3ad3a8b32e28cbf4b637607c710d69185a10e6d776732382d49d76`.

В APK проверены 10 обязательных путей и 22 файла моделей по размеру и SHA-256 из манифеста. Подпись проверена `apksigner`.

| Кейс | Реплик | RTF | JSON |
|---|---:|---:|---|
| `cats_c01_v0_meeting_farfield` | 24 | 0,201 | `stenoeval validate`: OK |
| `cats_c02_v0_call_phone` | 15 | 0,333 | `stenoeval validate`: OK |
| `cats_c03_v0_meeting_noise` | 20 | 0,301 | `stenoeval validate`: OK |

Сводные метрики по этим трём записям: **WER norm 20,26 %**, **cpWER 41,48 %**, **DER ±0,25 13,11 %**.

Исходные JSON и расширенные результаты лежат в `cats01/`, `cats02/`, `cats03/`; объединённые JSON по ТЗ — в `final/`. Маркеры `.done.json` содержат время по стадиям, память и предупреждения ресурсов.

Отдельная проверка в режиме полёта — `../hard-pixel-offline/`.
