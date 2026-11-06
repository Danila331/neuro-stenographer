# full_synth split=dev metric=norm_num

| model | ALL (n=789) | scenario=call | scenario=meeting | condition=clean | condition=farfield | condition=noise | condition=overlap | condition=phone | condition=reverb |
|---|---|---|---|---|---|---|---|---|---|
| gigaam2_ctc | 24.92 | 28.70 | 20.94 | 9.52 | 35.01 | 16.90 | 20.71 | 42.30 | 17.65 |
| gigaam3_ctc | 25.33 | 29.70 | 20.72 | 9.46 | 33.60 | 15.67 | 21.27 | 44.25 | 22.46 |
| gigaam3_ctc_punct | 22.65 | 25.40 | 19.76 | 10.06 | 30.91 | 15.89 | 20.85 | 35.44 | 18.98 |
| gigaam3_rnnt | 23.36 | 26.77 | 19.77 | 9.14 | 33.00 | 15.67 | 20.29 | 38.69 | 17.78 |
| gigaam3_rnnt_punct | 21.38 | 23.55 | 19.10 | 9.68 | 31.12 | 14.87 | 19.60 | 32.46 | 17.51 |
| zip_ru_2504_int8 | 31.63 | 35.45 | 27.60 | 12.78 | 45.16 | 23.36 | 25.45 | 48.61 | 31.82 |
| n слов | 10396 | 5334 | 5062 | 1839 | 1488 | 2367 | 1434 | 2520 | 748 |
