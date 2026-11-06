
### WER norm_num, % 

| модель | ru-asr-dev dev | ru-asr-dev test | dialog-synth (реплики) dev | dialog-synth (реплики) test | dialog-cats (реплики) dev | dialog-cats (реплики) test |
|---|---|---|---|---|---|---|
| gigaam3_ctc | 4.36 | 5.84 | 25.33 | 19.91 | 27.55 | 23.34 |
| gigaam3_rnnt | 4.09 | 5.63 | 23.36 | 17.57 | 24.04 | 20.08 |
| gigaam3_ctc_punct | 5.73 | 7.39 | 22.65 | 17.46 | 25.60 | 25.09 |
| gigaam3_rnnt_punct | 5.64 | 7.18 | 21.38 | 16.77 | 23.53 | 22.96 |
| gigaam2_ctc | 5.11 | 6.15 | 24.92 | 18.09 | 25.53 | 24.34 |
| zip_ru_2504_int8 | 7.26 | 9.58 | 31.63 | 23.74 | 29.23 | 26.85 |
| zip_ru_2504_fp32 | 7.15 | 9.24 |  |  |  |  |
| fc_multi_ctc | 12.93 | 15.56 |  |  |  |  |
| fc_multi_rnnt | 12.31 | 14.48 |  |  |  |  |
| parakeet_v3 | 11.64 | 14.70 |  |  |  |  |

### WER norm, % 

| модель | ru-asr-dev dev | ru-asr-dev test | dialog-synth (реплики) dev | dialog-synth (реплики) test | dialog-cats (реплики) dev | dialog-cats (реплики) test |
|---|---|---|---|---|---|---|
| gigaam3_ctc | 4.92 | 6.65 | 25.33 | 19.91 | 27.55 | 23.34 |
| gigaam3_rnnt | 4.64 | 6.43 | 23.36 | 17.57 | 24.04 | 20.08 |
| gigaam3_ctc_punct | 6.36 | 8.25 | 22.75 | 17.65 | 27.46 | 30.87 |
| gigaam3_rnnt_punct | 6.25 | 7.69 | 21.51 | 16.99 | 25.46 | 28.48 |
| gigaam2_ctc | 5.65 | 6.99 | 24.92 | 18.09 | 25.53 | 24.34 |
| zip_ru_2504_int8 | 7.81 | 10.41 | 31.63 | 23.74 | 29.23 | 26.85 |
| zip_ru_2504_fp32 | 7.70 | 10.10 |  |  |  |  |
| fc_multi_ctc | 13.49 | 16.41 |  |  |  |  |
| fc_multi_rnnt | 12.87 | 15.37 |  |  |  |  |
| parakeet_v3 | 11.96 | 14.98 |  |  |  |  |

### WER pc_sep_num, % (только файлы с эталоном со знаками и регистром)

| модель | ru-asr-dev dev | ru-asr-dev test | dialog-synth (реплики) dev | dialog-synth (реплики) test | dialog-cats (реплики) dev | dialog-cats (реплики) test |
|---|---|---|---|---|---|---|
| gigaam3_ctc | 33.74 | 35.46 | 52.88 | 47.43 | 61.61 | 56.52 |
| gigaam3_rnnt | 33.62 | 35.33 | 51.56 | 45.86 | 59.80 | 54.65 |
| gigaam3_ctc_punct | 16.80 | 18.17 | 37.65 | 33.18 | 38.99 | 40.14 |
| gigaam3_rnnt_punct | 14.66 | 17.07 | 34.82 | 30.83 | 29.78 | 32.54 |
| gigaam2_ctc | 34.44 | 35.69 | 52.59 | 46.31 | 60.84 | 57.94 |
| zip_ru_2504_int8 | 35.14 | 37.58 | 56.43 | 49.66 | 62.53 | 58.99 |
| zip_ru_2504_fp32 | 35.10 | 37.49 |  |  |  |  |
| fc_multi_ctc | 25.34 | 28.24 |  |  |  |  |
| fc_multi_rnnt | 24.56 | 29.16 |  |  |  |  |
| parakeet_v3 | 19.15 | 24.93 |  |  |  |  |

### WER pc_num, % (только файлы с эталоном со знаками и регистром)

| модель | ru-asr-dev dev | ru-asr-dev test | dialog-synth (реплики) dev | dialog-synth (реплики) test | dialog-cats (реплики) dev | dialog-cats (реплики) test |
|---|---|---|---|---|---|---|
| gigaam3_ctc | 37.47 | 38.95 | 56.55 | 51.39 | 66.46 | 62.98 |
| gigaam3_rnnt | 37.45 | 38.84 | 55.47 | 50.17 | 65.65 | 62.16 |
| gigaam3_ctc_punct | 19.40 | 20.22 | 42.27 | 38.06 | 45.49 | 48.22 |
| gigaam3_rnnt_punct | 16.98 | 19.17 | 39.83 | 36.39 | 36.34 | 40.65 |
| gigaam2_ctc | 38.09 | 39.22 | 56.40 | 50.50 | 65.23 | 62.79 |
| zip_ru_2504_int8 | 38.82 | 40.55 | 60.07 | 53.82 | 67.22 | 64.42 |
| zip_ru_2504_fp32 | 38.78 | 40.50 |  |  |  |  |
| fc_multi_ctc | 28.51 | 31.47 |  |  |  |  |
| fc_multi_rnnt | 27.64 | 31.63 |  |  |  |  |
| parakeet_v3 | 22.81 | 28.20 |  |  |  |  |
