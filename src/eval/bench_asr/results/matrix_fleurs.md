| model | kind | precision | size_mb | n | wer_raw | wer_norm | wer_norm_num | wer_raw_s40 | wer_norm_s40 | peak_rss_mb | load_s | rtf_acc_t4 | load_avg |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| gigaam2_ctc | nemo_ctc | int8 | 226 | 200 | 29.32 | 3.32 | 3.32 | 26.72 | 2.69 | 929 | 0.64 | 0.088 | 28.5 |
| gigaam2_rnnt | nemo_transducer | int8 | 230 | 200 | 29.61 | 3.58 | 3.58 | 26.99 | 3.1 | 934 | 0.81 | 0.178 | 107.7 |
| gigaam3_ctc | nemo_ctc | int8 | 214 | 200 | 29.44 | 2.75 | 2.75 | 27.4 | 3.1 | 571 | 1.23 | 0.057 | 81.1 |
| gigaam3_rnnt | nemo_transducer | int8 | 219 | 200 | 29.27 | 3.07 | 3.07 | 26.86 | 2.42 | 583 | 0.79 | 0.183 | 114.6 |
| parakeet_v3 | nemo_transducer | int8 | 639 | 200 | 11.8 | 6.96 | 6.9 | 10.66 | 5.38 | 1535 | 1.94 | 0.074 | 111.5 |
| vosk_small_stream | online_transducer | int8 | 27 | 200 | 33.19 | 8.89 | 8.89 | 31.04 | 9.02 | 319 | 0.99 | 0.06 | 82.2 |
| zip_ru_2504_int8 | transducer | int8 | 70 | 200 | 29.89 | 4.77 | 4.77 | 26.59 | 3.63 | 649 | 4.37 | 0.08 | 106.6 |
