# full_rudev split=test metric=norm_num

| model | ALL (n=202) | source=fleurs | source=golos_crowd | source=golos_farfield | source=ruls | source=voxforge | condition=clean | condition=farfield | condition=noise | condition=phone | condition=reverb |
|---|---|---|---|---|---|---|---|---|---|---|---|
| gigaam2_ctc | 6.15 | 4.38 | 4.74 | 6.10 | 5.44 | 11.56 | 5.83 | 5.00 | 4.85 | 4.74 | 14.84 |
| gigaam3_ctc | 5.84 | 4.62 | 3.28 | 5.49 | 5.29 | 10.85 | 5.38 | 3.57 | 1.94 | 6.84 | 16.41 |
| gigaam3_ctc_punct | 7.39 | 5.47 | 7.66 | 6.71 | 6.53 | 12.50 | 7.13 | 5.00 | 4.85 | 7.89 | 14.84 |
| gigaam3_rnnt | 5.63 | 4.50 | 2.92 | 4.27 | 5.29 | 10.61 | 5.38 | 2.86 | 2.91 | 5.79 | 14.06 |
| gigaam3_rnnt_punct | 7.18 | 5.11 | 8.76 | 4.27 | 5.91 | 13.21 | 7.13 | 4.29 | 3.88 | 5.79 | 15.62 |
| zip_ru_2504_int8 | 9.58 | 5.96 | 3.65 | 14.02 | 12.13 | 14.86 | 8.04 | 7.86 | 10.68 | 14.74 | 24.22 |
| n слов | 2327 | 822 | 274 | 164 | 643 | 424 | 1766 | 140 | 103 | 190 | 128 |
