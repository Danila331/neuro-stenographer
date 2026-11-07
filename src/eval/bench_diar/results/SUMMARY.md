# Итоги bench_diar (волна 2)

Метрика — сквозная (B22): слова кэша ASR refpipe-medium-rescue (dev) / тот же конфиг на test, turns -> Aligner (align по умолчанию:
max_gap 1500, island 1/400) -> `stenoeval` (DER ±0.25 с NIST, cpWER norm). dev = 84 файла (ru-synth 27, cats 44, en 9, mixed 4),
test = 26 файлов (ru-synth 9, cats 16, mixed 1; en-test не считался: в моей копии refpipe нет движка `nemo_tdt`).
Подбор — только на dev. Файлы: `final_dev_*.json`, `final_test_*.json`; сырые логи `*.log` не в git.

| метод (dev, 84) | DER ±.25 | DER 0 | JER | число говорящих угадано | cpWER |
|---|---|---|---|---|---|
| было: sherpa штатный (campplus, thr 0.85, shift 0.1) | 25.3 | 33.4 | 54.3 | 33 % | 51.6 |
| phrase, campplus (без pyannote) | 16.9 | 23.9 | 35.9 | 70 % | 33.2 |
| hybrid, campplus (lite) | 16.0 | 23.1 | 34.8 | 71 % | 31.5 |
| hybrid, eres2net_base_200k (medium) | 15.2 | 22.3 | 33.4 | 71 % | 30.7 |
| hybrid, eres2netv2 (hard) | 14.9 | 22.0 | 33.2 | 74 % | 30.6 |
| потолок (идеальные turns, тот же ASR+Aligner) | 10.3 | 17.0 | 21.9 | 98 % | 23.2 |

| метод (test, 26) | DER ±.25 | DER 0 | JER | число говорящих | cpWER |
|---|---|---|---|---|---|
| было | 25.3 | 34.2 | 54.7 | 35 % | 56.8 |
| phrase, campplus | 20.6 | 29.1 | 43.6 | 77 % | 47.0 |
| hybrid, campplus | 14.2 | 22.8 | 36.1 | 81 % | 36.9 |
| hybrid, eres2net_base_200k | 13.1 | 22.0 | 35.4 | 77 % | 35.8 |
| hybrid, eres2netv2 | 12.3 | 21.2 | 33.6 | 88 % | 35.2 |

Скорость (Mac M-серия, 4 потока, load average 5-8, `timing.txt`): штатный sherpa campplus RTF по длине записи 60/150/300/600 с:
shift 0.1 — 0.057/0.055/0.100/0.069 (300 с: 30 с, у eres2netv2 73.5 с = 0.245); shift 0.5 — 0.015/0.022/0.021/0.011; shift 1.0 —
0.008/0.009/0.009/0.006. Эмбеддинги юнитов (фразы): campplus 0.005-0.006, eres2net_base_200k 0.011, eres2netv2 0.019-0.022.
