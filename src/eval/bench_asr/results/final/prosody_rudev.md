
### full_rudev: пороги по dev: t_comma=0.25, t_period=0.6 (файлов со знаками в эталоне: dev 273, test 102)

| система | split | WER pc_sep_num | WER pc_num | WER norm_num |
|---|---|---|---|---|
| gigaam3_ctc (без знаков) | dev | 33.74 | 37.47 | 4.30 |
| gigaam3_ctc + просодия | dev | 23.00 | 26.54 | 4.30 |
| gigaam3_ctc + просодия (дефолт 0.25/0.6) | dev | 23.00 | 26.54 | 4.30 |
| gigaam3_ctc_punct | dev | 16.80 | 19.40 | 4.72 |
| gigaam3_rnnt_punct | dev | 14.66 | 16.98 | 4.86 |
| gigaam3_ctc (без знаков) | test | 34.44 | 38.06 | 4.67 |
| gigaam3_ctc + просодия | test | 25.23 | 28.56 | 4.67 |
| gigaam3_ctc + просодия (дефолт 0.25/0.6) | test | 25.23 | 28.56 | 4.67 |
| gigaam3_ctc_punct | test | 16.87 | 19.06 | 5.85 |
| gigaam3_rnnt_punct | test | 15.75 | 17.99 | 5.74 |
