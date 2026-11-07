# full_rudev split=dev metric=norm_num

| model | ALL (n=498) | source=fleurs | source=golos_crowd | source=golos_farfield | source=ruls | source=voxforge | condition=clean | condition=farfield | condition=noise | condition=phone | condition=reverb |
|---|---|---|---|---|---|---|---|---|---|---|---|
| gigaam2_ctc | 5.11 | 5.24 | 3.80 | 4.20 | 5.78 | 5.07 | 4.95 | 4.42 | 7.26 | 5.63 | 6.46 |
| gigaam3_ctc | 4.36 | 4.05 | 3.80 | 5.46 | 5.34 | 3.80 | 4.05 | 6.23 | 4.47 | 3.64 | 7.14 |
| gigaam3_ctc_punct | 5.73 | 3.82 | 13.42 | 7.35 | 6.67 | 4.83 | 5.27 | 8.05 | 7.82 | 5.96 | 7.82 |
| gigaam3_rnnt | 4.09 | 4.23 | 2.24 | 4.62 | 4.72 | 3.72 | 3.91 | 5.45 | 5.59 | 2.32 | 5.78 |
| gigaam3_rnnt_punct | 5.64 | 4.37 | 13.65 | 5.46 | 6.49 | 4.28 | 5.60 | 6.23 | 4.47 | 4.97 | 6.80 |
| zip_ru_2504_int8 | 7.26 | 6.44 | 3.80 | 7.77 | 9.88 | 7.37 | 6.73 | 8.05 | 10.06 | 6.95 | 12.59 |
| n слов | 5483 | 2174 | 447 | 476 | 1124 | 1262 | 4323 | 385 | 179 | 302 | 294 |
