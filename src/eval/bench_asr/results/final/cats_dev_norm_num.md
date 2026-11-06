# full_cats split=dev metric=norm_num

| model | ALL (n=966) | scenario=call | scenario=meeting | condition=clean | condition=farfield | condition=noise | condition=overlap | condition=phone | condition=reverb |
|---|---|---|---|---|---|---|---|---|---|
| gigaam2_ctc | 25.53 | 29.58 | 21.45 | 20.19 | 24.08 | 23.26 | 34.82 | 29.44 | 16.03 |
| gigaam3_ctc | 27.55 | 31.76 | 23.32 | 18.44 | 27.48 | 21.32 | 40.27 | 33.76 | 16.58 |
| gigaam3_ctc_punct | 25.60 | 28.70 | 22.48 | 17.90 | 25.85 | 21.00 | 37.94 | 29.29 | 17.93 |
| gigaam3_rnnt | 24.04 | 27.12 | 20.93 | 16.82 | 24.22 | 19.22 | 36.77 | 28.22 | 13.59 |
| gigaam3_rnnt_punct | 23.53 | 26.56 | 20.47 | 17.36 | 22.45 | 21.81 | 32.88 | 26.71 | 16.58 |
| zip_ru_2504_int8 | 29.23 | 31.85 | 26.59 | 21.00 | 36.05 | 23.42 | 37.55 | 32.70 | 17.93 |
| n слов | 4297 | 2157 | 2140 | 743 | 735 | 619 | 514 | 1318 | 368 |
