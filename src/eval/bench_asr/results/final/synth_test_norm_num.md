# full_synth split=test metric=norm_num

| model | ALL (n=262) | scenario=call | scenario=meeting | condition=clean | condition=farfield | condition=noise | condition=overlap | condition=phone | condition=reverb |
|---|---|---|---|---|---|---|---|---|---|
| gigaam2_ctc | 18.09 | 17.14 | 19.12 | 10.83 | 11.52 | 10.30 | 22.47 | 24.74 | 26.79 |
| gigaam3_ctc | 19.91 | 19.00 | 20.89 | 11.19 | 10.87 | 12.29 | 25.16 | 28.07 | 29.02 |
| gigaam3_ctc_punct | 17.46 | 17.14 | 17.80 | 11.90 | 10.87 | 12.46 | 22.01 | 22.04 | 22.10 |
| gigaam3_rnnt | 17.57 | 15.82 | 19.46 | 9.06 | 9.78 | 10.63 | 23.31 | 22.45 | 26.56 |
| gigaam3_rnnt_punct | 16.77 | 15.71 | 17.92 | 10.66 | 10.65 | 10.30 | 20.06 | 23.70 | 24.11 |
| zip_ru_2504_int8 | 23.74 | 22.03 | 25.59 | 12.97 | 21.74 | 21.10 | 27.21 | 27.44 | 30.58 |
| n слов | 3631 | 1884 | 1747 | 563 | 460 | 602 | 1077 | 481 | 448 |
