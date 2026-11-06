# full_synth split=test metric=pc_sep_num

| model | ALL (n=262) | scenario=call | scenario=meeting | condition=clean | condition=farfield | condition=noise | condition=overlap | condition=phone | condition=reverb |
|---|---|---|---|---|---|---|---|---|---|
| gigaam2_ctc | 45.58 | 46.42 | 44.66 | 42.15 | 40.66 | 41.90 | 47.93 | 49.04 | 50.53 |
| gigaam3_ctc | 46.72 | 47.89 | 45.45 | 42.56 | 40.84 | 43.51 | 49.44 | 51.61 | 50.53 |
| gigaam3_ctc_punct | 33.33 | 32.73 | 33.99 | 29.34 | 28.80 | 29.05 | 37.40 | 36.33 | 35.87 |
| gigaam3_rnnt | 45.06 | 45.63 | 44.43 | 41.74 | 39.79 | 42.03 | 47.93 | 47.59 | 49.12 |
| gigaam3_rnnt_punct | 31.12 | 30.26 | 32.06 | 27.69 | 24.61 | 27.04 | 34.76 | 33.28 | 36.57 |
| zip_ru_2504_int8 | 48.81 | 49.18 | 48.39 | 43.25 | 47.12 | 48.86 | 50.64 | 50.64 | 51.24 |
| n слов | 4563 | 2389 | 2174 | 726 | 573 | 747 | 1329 | 622 | 566 |
