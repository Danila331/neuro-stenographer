# full_synth split=dev metric=pc_sep_num

| model | ALL (n=789) | scenario=call | scenario=meeting | condition=clean | condition=farfield | condition=noise | condition=overlap | condition=phone | condition=reverb |
|---|---|---|---|---|---|---|---|---|---|
| gigaam2_ctc | 51.57 | 53.87 | 49.15 | 42.08 | 57.23 | 45.69 | 50.65 | 62.49 | 47.11 |
| gigaam3_ctc | 51.92 | 54.93 | 48.74 | 41.96 | 56.02 | 44.87 | 50.93 | 64.21 | 50.68 |
| gigaam3_ctc_punct | 37.66 | 39.70 | 35.52 | 27.76 | 43.76 | 33.16 | 37.16 | 46.45 | 35.54 |
| gigaam3_rnnt | 50.62 | 52.87 | 48.25 | 41.74 | 55.80 | 44.94 | 50.20 | 60.62 | 47.11 |
| gigaam3_rnnt_punct | 35.06 | 36.68 | 33.36 | 25.70 | 42.83 | 30.62 | 35.75 | 41.87 | 32.70 |
| zip_ru_2504_int8 | 55.39 | 57.92 | 52.73 | 43.24 | 62.89 | 49.19 | 53.29 | 66.64 | 56.05 |
| n слов | 13036 | 6696 | 6340 | 2331 | 1819 | 2946 | 1779 | 3210 | 951 |
