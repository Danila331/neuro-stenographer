# full_cats split=dev metric=pc_sep_num

| model | ALL (n=966) | scenario=call | scenario=meeting | condition=clean | condition=farfield | condition=noise | condition=overlap | condition=phone | condition=reverb |
|---|---|---|---|---|---|---|---|---|---|
| gigaam2_ctc | 60.84 | 62.96 | 58.70 | 60.92 | 58.69 | 59.56 | 64.51 | 61.64 | 59.15 |
| gigaam3_ctc | 61.61 | 63.99 | 59.20 | 60.15 | 60.18 | 57.13 | 67.18 | 64.18 | 58.19 |
| gigaam3_ctc_punct | 38.99 | 42.18 | 35.77 | 33.65 | 37.64 | 36.15 | 49.58 | 41.34 | 34.49 |
| gigaam3_rnnt | 59.80 | 61.36 | 58.23 | 59.01 | 59.09 | 56.20 | 64.65 | 61.15 | 57.42 |
| gigaam3_rnnt_punct | 29.78 | 33.17 | 26.37 | 24.79 | 27.41 | 28.16 | 40.85 | 32.73 | 21.77 |
| zip_ru_2504_int8 | 62.53 | 63.83 | 61.23 | 60.34 | 65.64 | 59.10 | 66.20 | 63.25 | 59.15 |
| n слов | 5960 | 2994 | 2966 | 1049 | 1007 | 863 | 710 | 1812 | 519 |
