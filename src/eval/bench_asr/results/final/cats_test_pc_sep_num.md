# full_cats split=test metric=pc_sep_num

| model | ALL (n=322) | scenario=call | scenario=meeting | condition=clean | condition=farfield | condition=noise | condition=overlap | condition=phone | condition=reverb |
|---|---|---|---|---|---|---|---|---|---|
| gigaam2_ctc | 57.94 | 59.70 | 56.31 | 54.15 | 53.92 | 60.70 | 65.92 | 54.52 | 65.19 |
| gigaam3_ctc | 56.52 | 58.46 | 54.72 | 54.15 | 52.04 | 58.82 | 64.63 | 54.37 | 58.01 |
| gigaam3_ctc_punct | 40.14 | 42.30 | 38.13 | 34.15 | 33.86 | 41.44 | 55.31 | 37.33 | 43.65 |
| gigaam3_rnnt | 54.65 | 56.18 | 53.22 | 51.38 | 50.78 | 58.02 | 62.70 | 52.00 | 56.35 |
| gigaam3_rnnt_punct | 32.54 | 35.93 | 29.39 | 22.15 | 27.59 | 30.75 | 49.84 | 30.81 | 40.33 |
| zip_ru_2504_int8 | 58.99 | 61.98 | 56.22 | 54.46 | 55.17 | 60.96 | 67.52 | 58.37 | 57.46 |
| n слов | 2185 | 1052 | 1133 | 325 | 319 | 374 | 311 | 675 | 181 |
