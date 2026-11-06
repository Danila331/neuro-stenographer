# full_cats split=test metric=norm_num

| model | ALL (n=322) | scenario=call | scenario=meeting | condition=clean | condition=farfield | condition=noise | condition=overlap | condition=phone | condition=reverb |
|---|---|---|---|---|---|---|---|---|---|
| gigaam2_ctc | 24.34 | 27.08 | 21.79 | 17.95 | 20.68 | 25.09 | 38.57 | 19.92 | 33.33 |
| gigaam3_ctc | 23.34 | 26.69 | 20.22 | 17.52 | 20.25 | 21.77 | 40.81 | 20.52 | 23.48 |
| gigaam3_ctc_punct | 25.09 | 27.47 | 22.88 | 20.51 | 21.10 | 23.62 | 43.95 | 21.33 | 25.76 |
| gigaam3_rnnt | 20.08 | 22.66 | 17.68 | 13.68 | 18.14 | 18.45 | 38.57 | 16.70 | 19.70 |
| gigaam3_rnnt_punct | 22.96 | 26.30 | 19.85 | 14.53 | 18.57 | 21.77 | 43.50 | 19.72 | 25.76 |
| zip_ru_2504_int8 | 26.85 | 31.51 | 22.52 | 17.09 | 24.47 | 24.72 | 43.95 | 26.96 | 23.48 |
| n слов | 1594 | 768 | 826 | 234 | 237 | 271 | 223 | 497 | 132 |
