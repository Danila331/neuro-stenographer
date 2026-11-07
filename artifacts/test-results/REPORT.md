# Результаты прогонов (stenoeval)

All error rates in %. WER/cpWER/DER are micro-averaged (sum of errors / sum of reference words or speech seconds); JER, ts MAE, spk acc are per-file means; RTF = sum(processing_ms)/sum(duration_ms). Main WER metrics: WER norm (no punctuation) and WER punct (punctuation and case counted, е=ё). WER raw/norm/punct/p-sep/p-lc/num = normalisation modes; DER ±.25 = NIST collar +-0.25 s around reference boundaries (pyannote collar=0.5).

## Сводка по прогонам

| run | tier | platform | device | dataset | commit | files | WER raw | WER norm | WER punct | WER p-sep | WER p-lc | WER num | cpWER | DER | DER ±.25 | JER | spk acc | ts start MAE ms | ts end MAE ms | start<300ms | RTF |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| refpipe-medium-baseline | medium | desktop | Darwin arm64 (arm) | refpipe-baseline-dev | e66816f5 | 84 | 59.30 | 24.39 | 58.80 | 54.73 | 34.23 | 23.92 | 52.87 | 35.78 | 27.97 | 54.70 | 33.33 | 374 | 460 | 70.96 | 0.130 |
| refpipe-medium-rescue | medium | desktop | Darwin arm64 (arm) | refpipe-baseline-dev | ea9e7334 | 84 | 57.38 | 21.89 | 56.85 | 52.56 | 31.96 | 21.42 | 51.56 | 33.43 | 25.30 | 54.32 | 33.33 | 281 | 382 | 72.12 | 0.030 |
| refpipe-medium-norelabel-dev | medium | desktop | Darwin arm64 (arm) | refpipe-baseline-dev | 75658d52 | 84 | 56.41 | 22.60 | 55.86 | 51.51 | 31.06 | 22.10 | 54.92 | 36.06 | 28.25 | 54.07 | 29.76 | 315 | 427 | 71.74 | 0.040 |
| refpipe-medium-relabel | medium | desktop | Darwin arm64 (arm) | refpipe-baseline-dev | 75658d52 | 84 | 56.41 | 22.60 | 55.86 | 51.51 | 31.06 | 22.10 | 32.14 | 23.11 | 15.97 | 34.72 | 66.67 | 315 | 427 | 71.74 | 0.071 |
| refpipe-medium-norelabel-test | medium | desktop | Darwin arm64 (arm) | refpipe-baseline-test | 75658d52 | 31 | 54.46 | 21.74 | 53.81 | 48.81 | 29.87 | 20.60 | 49.20 | 32.37 | 23.97 | 48.20 | 41.94 | 268 | 359 | 74.27 | 0.039 |
| refpipe-medium-relabel-test | medium | desktop | Darwin arm64 (arm) | refpipe-baseline-test | 75658d52 | 31 | 54.46 | 21.74 | 53.81 | 48.81 | 29.87 | 20.60 | 29.85 | 19.79 | 11.69 | 29.64 | 77.42 | 268 | 359 | 74.27 | 0.066 |
| refpipe-bp-robust-dev-bp0.0 | medium | desktop | Darwin arm64 (arm) | refpipe-baseline-dev | bcb022d8 | 84 | 56.41 | 22.60 | 55.86 | 51.51 | 31.06 | 22.10 | 32.14 | 23.11 | 15.97 | 34.72 | 66.67 | 315 | 427 | 71.74 | 0.000 |
| refpipe-bp-robust-dev-bp1.0 | medium | desktop | Darwin arm64 (arm) | refpipe-baseline-dev | bcb022d8 | 84 | 55.85 | 21.87 | 55.29 | 50.96 | 30.49 | 21.35 | 31.30 | 21.73 | 14.64 | 33.55 | 64.29 | 291 | 424 | 74.39 | 0.000 |
| refpipe-bp-robust-dev-bp1.0-k1thr030 | medium | desktop | Darwin arm64 (arm) | refpipe-baseline-dev | 68a857c0 | 84 | 55.85 | 21.87 | 55.29 | 50.96 | 30.49 | 21.35 | 31.03 | 21.58 | 14.54 | 32.71 | 66.67 | 291 | 424 | 74.39 | 0.020 |
| refpipe-bp-robust-test-bp0.0 | medium | desktop | Darwin arm64 (arm) | refpipe-baseline-test | bcb022d8 | 31 | 54.46 | 21.74 | 53.81 | 48.81 | 29.87 | 20.60 | 29.85 | 19.79 | 11.69 | 29.64 | 77.42 | 268 | 359 | 74.27 | 0.042 |
| refpipe-bp-robust-test-bp1.0 | medium | desktop | Darwin arm64 (arm) | refpipe-baseline-test | bcb022d8 | 31 | 54.13 | 21.38 | 53.48 | 48.52 | 29.74 | 20.08 | 31.42 | 19.77 | 11.71 | 31.56 | 77.42 | 256 | 346 | 77.06 | 0.046 |
| refpipe-bp-robust-test-bp1.0-k1thr030 | medium | desktop | Darwin arm64 (arm) | refpipe-baseline-test | 68a857c0 | 31 | 54.13 | 21.38 | 53.48 | 48.52 | 29.74 | 20.08 | 30.54 | 19.29 | 11.29 | 29.75 | 80.65 | 256 | 346 | 77.06 | 0.020 |

## refpipe-medium-baseline

tier=medium, platform=desktop, device=Darwin arm64 (arm), threads=4, dataset=refpipe-baseline-dev, config_hash=efab5936f22b028b7bd19fd50be6499e920b6b83dced1ae3a06c0bc394bd89a5

### По условиям

| group | files | WER raw | WER norm | WER punct | WER p-sep | WER p-lc | WER num | cpWER | DER | DER ±.25 | JER | spk acc | ts start MAE ms | ts end MAE ms | start<300ms | RTF |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ALL | 84 | 59.30 | 24.39 | 58.80 | 54.73 | 34.23 | 23.92 | 52.87 | 35.78 | 27.97 | 54.70 | 33.33 | 374 | 460 | 70.96 | 0.130 |
| clean | 17 | 59.17 | 10.71 | 58.67 | 55.01 | 22.36 | 10.30 | 25.67 | 17.03 | 9.16 | 35.49 | 52.94 | 182 | 301 | 82.27 | 0.129 |
| farfield | 12 | 63.80 | 25.45 | 63.38 | 60.27 | 35.75 | 24.91 | 65.32 | 42.86 | 34.47 | 56.31 | 50.00 | 318 | 355 | 69.46 | 0.112 |
| noise | 14 | 47.94 | 19.63 | 47.07 | 42.24 | 29.45 | 19.21 | 32.75 | 21.06 | 13.26 | 35.85 | 42.86 | 265 | 339 | 74.89 | 0.187 |
| overlap | 11 | 59.36 | 19.40 | 58.82 | 55.41 | 31.02 | 19.29 | 37.72 | 24.12 | 14.33 | 49.66 | 36.36 | 268 | 433 | 67.85 | 0.137 |
| phone | 22 | 68.76 | 42.47 | 68.57 | 64.17 | 48.90 | 41.93 | 84.76 | 60.60 | 55.39 | 77.31 | 9.09 | 793 | 848 | 59.51 | 0.111 |
| reverb | 8 | 39.12 | 17.80 | 38.25 | 33.38 | 29.04 | 16.55 | 75.58 | 49.17 | 38.66 | 70.86 | 12.50 | 260 | 339 | 78.06 | 0.087 |

### По файлам

| file | condition | dur s | spk ref/hyp | WER raw | WER norm | cpWER | DER | JER | RTF | notes |
|---|---|---|---|---|---|---|---|---|---|---|
| synth_0000_meeting_overlap | overlap | 278.5 | 2/2 | 38.95 | 18.09 | 20.60 | 12.16 | 18.70 | 0.1116 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0001_call_phone | phone | 217.5 | 2/2 | 59.06 | 41.81 | 47.21 | 42.59 | 54.67 | 0.1127 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0002_meeting_farfield | farfield | 161.6 | 3/2 | 64.93 | 53.31 | 52.96 | 46.71 | 50.78 | 0.0943 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0003_call_phone | phone | 268.7 | 2/1 | 92.38 | 90.20 | 96.49 | 89.86 | 82.19 | 0.1056 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0004_meeting_clean | clean | 150.5 | 2/2 | 26.48 | 8.23 | 8.54 | 4.24 | 4.17 | 0.1489 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0006_meeting_clean | clean | 109.2 | 4/4 | 27.80 | 10.17 | 10.59 | 11.22 | 16.92 | 0.1305 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0007_call_reverb | reverb | 71.4 | 3/4 | 61.06 | 48.18 | 81.82 | 56.83 | 54.48 | 0.096 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0010_meeting_noise | noise | 182.7 | 2/3 | 47.32 | 24.80 | 47.00 | 24.12 | 31.33 | 0.1567 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0011_call_phone | phone | 109.0 | 3/1 | 31.75 | 13.66 | 115.12 | 63.01 | 87.30 | 0.2207 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0012_meeting_noise | noise | 261.4 | 3/5 | 37.40 | 16.06 | 25.30 | 15.46 | 16.69 | 0.3506 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0013_call_clean | clean | 143.3 | 2/3 | 28.48 | 9.38 | 12.50 | 17.20 | 15.39 | 0.1966 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0014_meeting_farfield | farfield | 152.2 | 2/2 | 43.07 | 17.58 | 57.58 | 34.32 | 46.36 | 0.1882 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0015_call_overlap | overlap | 208.8 | 4/4 | 46.73 | 24.92 | 30.51 | 15.51 | 17.07 | 0.2 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0016_meeting_farfield | farfield | 249.0 | 2/2 | 36.74 | 17.26 | 35.62 | 20.75 | 33.70 | 0.1767 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0017_call_phone | phone | 75.9 | 4/1 | 49.23 | 35.71 | 122.22 | 71.73 | 91.71 | 0.1932 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0018_meeting_noise | noise | 245.8 | 4/5 | 36.59 | 12.98 | 25.38 | 15.77 | 19.11 | 0.2537 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0019_call_noise | noise | 91.3 | 2/2 | 32.81 | 12.94 | 12.94 | 18.11 | 16.56 | 0.1497 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0023_call_phone | phone | 178.7 | 4/2 | 68.83 | 57.73 | 72.27 | 52.61 | 70.20 | 0.1105 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0024_meeting_reverb | reverb | 249.3 | 2/1 | 37.24 | 12.98 | 62.79 | 35.03 | 66.36 | 0.0946 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0025_call_noise | noise | 255.5 | 2/3 | 38.88 | 18.53 | 19.66 | 7.74 | 10.71 | 0.1453 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0026_meeting_reverb | reverb | 73.1 | 4/3 | 39.68 | 22.95 | 59.84 | 43.30 | 50.88 | 0.0912 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0027_call_clean | clean | 243.2 | 2/2 | 40.10 | 12.16 | 12.64 | 5.85 | 5.62 | 0.1409 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0028_meeting_farfield | farfield | 229.3 | 2/2 | 58.77 | 46.54 | 57.28 | 40.99 | 41.92 | 0.0982 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0029_call_phone | phone | 82.3 | 2/1 | 57.06 | 47.98 | 105.78 | 58.04 | 76.70 | 0.0836 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0031_call_phone | phone | 119.4 | 2/1 | 53.51 | 41.31 | 89.96 | 60.17 | 77.06 | 0.1206 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0034_meeting_clean | clean | 191.1 | 4/5 | 32.65 | 10.21 | 28.01 | 15.10 | 15.22 | 0.133 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| synth_0035_call_overlap | overlap | 122.8 | 3/5 | 29.92 | 17.83 | 39.92 | 31.94 | 29.21 | 0.176 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c01-c02_v1_call_phone | phone | 87.8 | 3/1 | 39.45 | 25.69 | 105.51 | 58.13 | 83.27 | 0.141 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c01_v0_meeting_clean | clean | 58.6 | 3/3 | 28.44 | 9.17 | 25.69 | 17.12 | 27.69 | 0.1253 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c02-c06_v1_meeting_farfield | farfield | 93.5 | 3/3 | 32.29 | 17.19 | 90.62 | 63.61 | 70.87 | 0.1145 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c02_v0_call_phone | phone | 41.4 | 3/1 | 40.37 | 25.69 | 100.00 | 57.46 | 83.35 | 0.0833 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c06-c09_v1_meeting_farfield | farfield | 88.4 | 3/4 | 39.78 | 23.33 | 74.44 | 55.77 | 59.77 | 0.0723 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c06_v0_call_noise | noise | 31.9 | 3/2 | 34.94 | 16.87 | 73.49 | 41.63 | 52.73 | 0.0938 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c07-c08_v1_call_phone | phone | 45.7 | 2/2 | 41.59 | 19.47 | 45.13 | 36.88 | 43.55 | 0.1073 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c07_v0_meeting_reverb | reverb | 22.7 | 2/2 | 56.41 | 23.08 | 69.23 | 105.92 | 77.17 | 0.1412 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c08-c10_v1_meeting_reverb | reverb | 66.2 | 2/1 | 29.77 | 15.38 | 103.85 | 67.63 | 76.98 | 0.0521 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c08_v0_call_reverb | reverb | 29.1 | 2/1 | 33.78 | 18.92 | 97.30 | 61.43 | 75.15 | 0.0486 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c09-c11_v1_call_phone | phone | 61.7 | 3/1 | 36.98 | 21.28 | 123.40 | 76.63 | 89.01 | 0.081 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c09_v0_meeting_overlap | overlap | 30.1 | 3/3 | 50.00 | 36.08 | 77.32 | 67.64 | 69.67 | 0.0602 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c10-c12_v1_meeting_noise | noise | 55.9 | 2/2 | 44.54 | 24.58 | 32.20 | 27.39 | 27.37 | 0.1276 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c10_v0_call_clean | clean | 25.6 | 2/1 | 29.83 | 8.93 | 103.57 | 60.12 | 75.99 | 0.0724 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c11-c13_v1_call_clean | clean | 57.8 | 3/2 | 36.69 | 16.36 | 102.42 | 60.11 | 79.34 | 0.0945 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c11_v0_meeting_noise | noise | 44.0 | 3/2 | 28.72 | 10.99 | 74.72 | 47.42 | 62.03 | 0.084 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c12_v0_call_phone | phone | 18.9 | 2/1 | 48.39 | 27.42 | 74.19 | 54.64 | 71.33 | 0.0588 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c13-c16_v1_call_clean | clean | 45.1 | 3/3 | 47.86 | 24.14 | 50.00 | 36.37 | 43.54 | 0.0991 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c13_v0_meeting_reverb | reverb | 40.8 | 3/1 | 38.67 | 6.76 | 105.40 | 68.39 | 85.22 | 0.0611 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c14-c23_v1_meeting_farfield | farfield | 84.4 | 4/3 | 36.47 | 22.75 | 106.59 | 78.26 | 81.60 | 0.0555 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c14_v0_call_overlap | overlap | 26.5 | 4/3 | 60.24 | 44.30 | 70.89 | 57.06 | 68.15 | 0.1304 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c16-c18_v1_meeting_clean | clean | 59.8 | 3/2 | 38.18 | 7.27 | 57.27 | 53.39 | 58.22 | 0.1488 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c16_v0_call_clean | clean | 20.4 | 3/1 | 57.14 | 23.81 | 114.29 | 65.59 | 84.47 | 0.0817 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c17-c20_v1_call_phone | phone | 44.3 | 2/1 | 33.33 | 15.08 | 89.68 | 60.53 | 76.00 | 0.0891 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c17_v0_meeting_reverb | reverb | 28.7 | 2/1 | 28.30 | 5.66 | 86.79 | 80.35 | 80.62 | 0.0942 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c18-c19_v1_meeting_farfield | farfield | 66.6 | 3/2 | 58.09 | 36.50 | 94.16 | 69.65 | 82.97 | 0.0691 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c18_v0_call_phone | phone | 24.9 | 3/1 | 29.41 | 10.29 | 116.18 | 78.02 | 87.95 | 0.0657 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c19-c21_v1_call_phone | phone | 59.9 | 3/0 | 100.00 | 100.00 | 100.00 | 100.00 | 100.00 | 0.0419 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c19_v0_meeting_overlap | overlap | 26.1 | 3/2 | 54.41 | 33.33 | 85.51 | 50.77 | 68.04 | 0.0936 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c20-c22_v1_meeting_noise | noise | 69.2 | 2/2 | 25.95 | 4.58 | 44.27 | 37.62 | 45.63 | 0.1781 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c20_v0_call_phone | phone | 26.9 | 2/1 | 28.77 | 6.85 | 80.82 | 67.36 | 78.03 | 0.1625 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c21_v0_meeting_phone | phone | 40.3 | 3/2 | 30.23 | 19.77 | 98.84 | 57.32 | 77.39 | 0.0997 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c22_v0_call_clean | clean | 16.1 | 2/2 | 50.00 | 25.86 | 60.35 | 48.41 | 54.79 | 0.0763 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c23_v0_meeting_clean | clean | 42.9 | 4/3 | 31.03 | 18.18 | 47.73 | 30.91 | 56.13 | 0.117 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c25_v0_meeting_noise | noise | 48.5 | 2/2 | 32.14 | 19.28 | 19.28 | 14.96 | 14.85 | 0.1127 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c26_v0_call_phone | phone | 25.5 | 3/1 | 28.12 | 15.62 | 118.75 | 74.17 | 88.49 | 0.0953 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c28_v0_call_noise | noise | 23.7 | 3/2 | 31.67 | 16.13 | 67.74 | 38.45 | 66.08 | 0.0975 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c29_v0_meeting_farfield | farfield | 26.0 | 2/1 | 49.15 | 23.73 | 64.41 | 63.43 | 73.58 | 0.0927 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c32_v0_call_noise | noise | 19.3 | 2/2 | 54.90 | 45.10 | 47.06 | 48.71 | 58.31 | 0.0892 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c34_v0_call_overlap | overlap | 30.5 | 2/2 | 40.54 | 10.81 | 27.03 | 21.59 | 26.62 | 0.0956 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c36_v0_call_phone | phone | 26.1 | 2/1 | 54.10 | 43.33 | 75.00 | 63.35 | 76.97 | 0.1812 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c37_v0_meeting_overlap | overlap | 25.2 | 3/1 | 25.00 | 20.31 | 31.25 | 33.16 | 74.74 | 0.1691 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c39_v0_meeting_overlap | overlap | 26.0 | 4/2 | 63.08 | 35.38 | 86.15 | 60.78 | 75.97 | 0.1514 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| cats_c40_v0_call_overlap | overlap | 21.5 | 3/2 | 60.00 | 45.45 | 89.39 | 59.98 | 71.32 | 0.0802 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| ensynth_0001_call_clean | clean | 166.8 | 2/2 | 98.98 | 8.39 | 8.88 | 8.28 | 8.21 | 0.1113 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| ensynth_0003_call_overlap | overlap | 216.8 | 2/3 | 99.38 | 8.53 | 36.22 | 20.96 | 26.74 | 0.1103 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| ensynth_0004_meeting_clean | clean | 158.6 | 3/4 | 97.63 | 2.10 | 20.00 | 14.45 | 20.41 | 0.116 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| ensynth_0005_call_noise | noise | 125.5 | 2/2 | 97.54 | 20.00 | 35.42 | 20.44 | 26.48 | 0.0926 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| ensynth_0008_meeting_farfield | farfield | 103.1 | 2/2 | 98.90 | 12.68 | 27.46 | 16.11 | 23.38 | 0.0597 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| ensynth_0009_call_phone | phone | 138.9 | 2/1 | 99.55 | 8.37 | 96.03 | 54.01 | 75.55 | 0.0575 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| ensynth_0011_call_clean | clean | 134.4 | 3/3 | 99.09 | 6.32 | 19.86 | 10.45 | 15.59 | 0.1055 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| ensynth_0012_meeting_farfield | farfield | 232.5 | 2/6 | 97.79 | 13.53 | 88.23 | 50.31 | 47.82 | 0.1005 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| ensynth_0013_call_phone | phone | 176.0 | 3/2 | 97.36 | 24.16 | 45.30 | 19.85 | 46.87 | 0.097 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| mix_0000_meeting_noise | noise | 103.8 | 3/4 | 89.26 | 48.35 | 52.89 | 45.87 | 54.01 | 0.0975 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| mix_0002_meeting_clean | clean | 125.6 | 4/4 | 80.21 | 24.91 | 40.83 | 21.66 | 21.60 | 0.105 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| mix_0003_call_phone | phone | 122.1 | 3/1 | 95.36 | 71.03 | 81.34 | 78.81 | 83.20 | 0.1045 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |
| mix_0004_meeting_farfield | farfield | 74.5 | 4/4 | 96.27 | 39.13 | 70.19 | 46.78 | 62.91 | 0.0802 | refpipe py reference, medium (greedy), 4+4 threads, M-series Mac, load<12, no oracle speakers |

## refpipe-medium-rescue

tier=medium, platform=desktop, device=Darwin arm64 (arm), threads=4, dataset=refpipe-baseline-dev, config_hash=498421339f35e51320b7af5f60c9df1f188cbf0d78c9b73e3702c47a6c1b1b18

### По условиям

| group | files | WER raw | WER norm | WER punct | WER p-sep | WER p-lc | WER num | cpWER | DER | DER ±.25 | JER | spk acc | ts start MAE ms | ts end MAE ms | start<300ms | RTF |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ALL | 84 | 57.38 | 21.89 | 56.85 | 52.56 | 31.96 | 21.42 | 51.56 | 33.43 | 25.30 | 54.32 | 33.33 | 281 | 382 | 72.12 | 0.030 |
| clean | 17 | 59.19 | 10.73 | 58.69 | 55.05 | 22.42 | 10.32 | 25.69 | 17.03 | 9.15 | 35.49 | 52.94 | 182 | 301 | 82.27 | 0.032 |
| farfield | 12 | 63.80 | 25.45 | 63.38 | 60.27 | 35.75 | 24.91 | 65.32 | 42.86 | 34.47 | 56.31 | 50.00 | 318 | 355 | 69.46 | 0.028 |
| noise | 14 | 47.94 | 19.63 | 47.07 | 42.24 | 29.45 | 19.21 | 32.75 | 21.06 | 13.26 | 35.85 | 42.86 | 265 | 339 | 74.89 | 0.032 |
| overlap | 11 | 58.68 | 18.57 | 58.14 | 54.66 | 30.33 | 18.46 | 36.85 | 23.91 | 14.08 | 49.56 | 36.36 | 260 | 432 | 67.85 | 0.033 |
| phone | 22 | 61.49 | 32.91 | 61.17 | 56.03 | 40.35 | 32.38 | 79.86 | 51.06 | 44.40 | 76.01 | 9.09 | 371 | 487 | 63.94 | 0.027 |
| reverb | 8 | 39.74 | 18.78 | 38.86 | 34.12 | 29.85 | 17.53 | 76.57 | 48.56 | 37.93 | 70.60 | 12.50 | 261 | 344 | 78.06 | 0.031 |

### По файлам

| file | condition | dur s | spk ref/hyp | WER raw | WER norm | cpWER | DER | JER | RTF | notes |
|---|---|---|---|---|---|---|---|---|---|---|
| synth_0000_meeting_overlap | overlap | 278.5 | 2/2 | 38.95 | 18.09 | 20.60 | 12.16 | 18.70 | 0.0354 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0001_call_phone | phone | 217.5 | 2/2 | 55.47 | 36.80 | 47.59 | 40.02 | 54.34 | 0.0242 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0002_meeting_farfield | farfield | 161.6 | 3/2 | 64.93 | 53.31 | 52.96 | 46.71 | 50.78 | 0.0222 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0003_call_phone | phone | 268.7 | 2/1 | 52.15 | 33.92 | 55.99 | 29.12 | 61.38 | 0.0258 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0004_meeting_clean | clean | 150.5 | 2/2 | 26.48 | 8.23 | 8.54 | 4.24 | 4.17 | 0.0281 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0006_meeting_clean | clean | 109.2 | 4/4 | 28.22 | 10.17 | 10.59 | 11.26 | 16.94 | 0.0286 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0007_call_reverb | reverb | 71.4 | 3/4 | 60.18 | 49.09 | 82.73 | 56.11 | 54.02 | 0.0308 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0010_meeting_noise | noise | 182.7 | 2/3 | 47.32 | 24.80 | 47.00 | 24.12 | 31.33 | 0.0298 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0011_call_phone | phone | 109.0 | 3/1 | 31.75 | 13.66 | 115.12 | 63.01 | 87.30 | 0.0264 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0012_meeting_noise | noise | 261.4 | 3/5 | 37.40 | 16.06 | 25.30 | 15.46 | 16.69 | 0.0287 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0013_call_clean | clean | 143.3 | 2/3 | 28.48 | 9.72 | 12.85 | 17.12 | 15.32 | 0.0307 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0014_meeting_farfield | farfield | 152.2 | 2/2 | 43.07 | 17.58 | 57.58 | 34.32 | 46.36 | 0.0279 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0015_call_overlap | overlap | 208.8 | 4/4 | 46.73 | 24.92 | 30.51 | 15.51 | 17.07 | 0.0299 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0016_meeting_farfield | farfield | 249.0 | 2/2 | 36.74 | 17.26 | 35.62 | 20.75 | 33.70 | 0.0329 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0017_call_phone | phone | 75.9 | 4/1 | 49.23 | 35.71 | 122.22 | 71.73 | 91.71 | 0.0363 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0018_meeting_noise | noise | 245.8 | 4/5 | 36.59 | 12.98 | 25.38 | 15.77 | 19.11 | 0.0319 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0019_call_noise | noise | 91.3 | 2/2 | 32.81 | 12.94 | 12.94 | 18.11 | 16.56 | 0.0317 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0023_call_phone | phone | 178.7 | 4/2 | 66.37 | 54.55 | 69.55 | 48.43 | 67.74 | 0.0271 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0024_meeting_reverb | reverb | 249.3 | 2/1 | 37.24 | 12.98 | 62.79 | 35.03 | 66.36 | 0.0294 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0025_call_noise | noise | 255.5 | 2/3 | 38.88 | 18.53 | 19.66 | 7.74 | 10.71 | 0.0293 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0026_meeting_reverb | reverb | 73.1 | 4/3 | 46.03 | 31.15 | 68.03 | 39.41 | 49.25 | 0.0292 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0027_call_clean | clean | 243.2 | 2/2 | 40.10 | 12.16 | 12.64 | 5.85 | 5.62 | 0.0321 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0028_meeting_farfield | farfield | 229.3 | 2/2 | 58.77 | 46.54 | 57.28 | 40.99 | 41.92 | 0.0331 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0029_call_phone | phone | 82.3 | 2/1 | 57.06 | 48.55 | 108.09 | 55.73 | 75.68 | 0.0301 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0031_call_phone | phone | 119.4 | 2/1 | 51.66 | 37.07 | 90.35 | 61.00 | 77.62 | 0.0281 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0034_meeting_clean | clean | 191.1 | 4/5 | 32.65 | 10.21 | 28.01 | 15.10 | 15.22 | 0.0299 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| synth_0035_call_overlap | overlap | 122.8 | 3/5 | 22.73 | 8.91 | 30.62 | 30.19 | 28.14 | 0.0287 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c01-c02_v1_call_phone | phone | 87.8 | 3/1 | 38.99 | 24.77 | 107.80 | 58.08 | 83.76 | 0.0288 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c01_v0_meeting_clean | clean | 58.6 | 3/3 | 28.44 | 9.17 | 25.69 | 17.12 | 27.69 | 0.0305 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c02-c06_v1_meeting_farfield | farfield | 93.5 | 3/3 | 32.29 | 17.19 | 90.62 | 63.61 | 70.87 | 0.0295 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c02_v0_call_phone | phone | 41.4 | 3/1 | 40.37 | 25.69 | 100.00 | 57.46 | 83.35 | 0.0316 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c06-c09_v1_meeting_farfield | farfield | 88.4 | 3/4 | 39.78 | 23.33 | 74.44 | 55.77 | 59.77 | 0.0294 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c06_v0_call_noise | noise | 31.9 | 3/2 | 34.94 | 16.87 | 73.49 | 41.63 | 52.73 | 0.0307 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c07-c08_v1_call_phone | phone | 45.7 | 2/2 | 41.59 | 19.47 | 45.13 | 36.88 | 43.55 | 0.0295 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c07_v0_meeting_reverb | reverb | 22.7 | 2/2 | 56.41 | 23.08 | 69.23 | 105.92 | 77.17 | 0.0312 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c08-c10_v1_meeting_reverb | reverb | 66.2 | 2/1 | 29.77 | 15.38 | 103.85 | 67.63 | 76.98 | 0.0288 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c08_v0_call_reverb | reverb | 29.1 | 2/1 | 33.78 | 18.92 | 97.30 | 61.43 | 75.15 | 0.0309 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c09-c11_v1_call_phone | phone | 61.7 | 3/1 | 36.98 | 21.28 | 123.40 | 76.63 | 89.01 | 0.0296 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c09_v0_meeting_overlap | overlap | 30.1 | 3/3 | 50.00 | 36.08 | 77.32 | 67.64 | 69.67 | 0.0346 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c10-c12_v1_meeting_noise | noise | 55.9 | 2/2 | 44.54 | 24.58 | 32.20 | 27.39 | 27.37 | 0.0509 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c10_v0_call_clean | clean | 25.6 | 2/1 | 29.83 | 8.93 | 103.57 | 60.12 | 75.99 | 0.0668 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c11-c13_v1_call_clean | clean | 57.8 | 3/2 | 36.69 | 16.36 | 102.42 | 60.11 | 79.34 | 0.0428 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c11_v0_meeting_noise | noise | 44.0 | 3/2 | 28.72 | 10.99 | 74.72 | 47.42 | 62.03 | 0.0423 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c12_v0_call_phone | phone | 18.9 | 2/1 | 48.39 | 27.42 | 74.19 | 54.64 | 71.33 | 0.036 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c13-c16_v1_call_clean | clean | 45.1 | 3/3 | 47.86 | 24.14 | 50.00 | 36.37 | 43.54 | 0.0508 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c13_v0_meeting_reverb | reverb | 40.8 | 3/1 | 38.67 | 6.76 | 105.40 | 68.39 | 85.22 | 0.0492 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c14-c23_v1_meeting_farfield | farfield | 84.4 | 4/3 | 36.47 | 22.75 | 106.59 | 78.26 | 81.60 | 0.0385 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c14_v0_call_overlap | overlap | 26.5 | 4/3 | 60.24 | 44.30 | 70.89 | 57.06 | 68.15 | 0.04 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c16-c18_v1_meeting_clean | clean | 59.8 | 3/2 | 38.18 | 7.27 | 57.27 | 53.39 | 58.22 | 0.058 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c16_v0_call_clean | clean | 20.4 | 3/1 | 57.14 | 23.81 | 114.29 | 65.59 | 84.47 | 0.0543 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c17-c20_v1_call_phone | phone | 44.3 | 2/1 | 33.33 | 15.08 | 89.68 | 60.53 | 76.00 | 0.0384 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c17_v0_meeting_reverb | reverb | 28.7 | 2/1 | 28.30 | 5.66 | 86.79 | 80.35 | 80.62 | 0.0365 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c18-c19_v1_meeting_farfield | farfield | 66.6 | 3/2 | 58.09 | 36.50 | 94.16 | 69.65 | 82.97 | 0.0455 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c18_v0_call_phone | phone | 24.9 | 3/1 | 29.41 | 10.29 | 116.18 | 78.02 | 87.95 | 0.0361 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c19-c21_v1_call_phone | phone | 59.9 | 3/1 | 77.27 | 65.16 | 94.19 | 75.57 | 86.59 | 0.0424 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c19_v0_meeting_overlap | overlap | 26.1 | 3/2 | 54.41 | 33.33 | 85.51 | 50.77 | 68.04 | 0.0649 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c20-c22_v1_meeting_noise | noise | 69.2 | 2/2 | 25.95 | 4.58 | 44.27 | 37.62 | 45.63 | 0.0417 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c20_v0_call_phone | phone | 26.9 | 2/1 | 28.77 | 6.85 | 80.82 | 67.36 | 78.03 | 0.0357 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c21_v0_meeting_phone | phone | 40.3 | 3/2 | 30.23 | 19.77 | 98.84 | 57.32 | 77.39 | 0.04 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c22_v0_call_clean | clean | 16.1 | 2/2 | 50.00 | 25.86 | 60.35 | 48.41 | 54.79 | 0.0603 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c23_v0_meeting_clean | clean | 42.9 | 4/3 | 31.03 | 18.18 | 47.73 | 30.91 | 56.13 | 0.0393 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c25_v0_meeting_noise | noise | 48.5 | 2/2 | 32.14 | 19.28 | 19.28 | 14.96 | 14.85 | 0.0529 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c26_v0_call_phone | phone | 25.5 | 3/1 | 28.12 | 15.62 | 118.75 | 74.17 | 88.49 | 0.0446 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c28_v0_call_noise | noise | 23.7 | 3/2 | 31.67 | 16.13 | 67.74 | 38.45 | 66.08 | 0.0443 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c29_v0_meeting_farfield | farfield | 26.0 | 2/1 | 49.15 | 23.73 | 64.41 | 63.43 | 73.58 | 0.0355 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c32_v0_call_noise | noise | 19.3 | 2/2 | 54.90 | 45.10 | 47.06 | 48.71 | 58.31 | 0.0411 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c34_v0_call_overlap | overlap | 30.5 | 2/2 | 40.54 | 10.81 | 27.03 | 21.59 | 26.62 | 0.0494 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c36_v0_call_phone | phone | 26.1 | 2/1 | 54.10 | 43.33 | 75.00 | 63.35 | 76.97 | 0.0358 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c37_v0_meeting_overlap | overlap | 25.2 | 3/1 | 25.00 | 20.31 | 31.25 | 33.16 | 74.74 | 0.0478 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c39_v0_meeting_overlap | overlap | 26.0 | 4/2 | 63.08 | 35.38 | 86.15 | 60.78 | 75.97 | 0.0669 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| cats_c40_v0_call_overlap | overlap | 21.5 | 3/2 | 60.00 | 45.45 | 89.39 | 59.98 | 71.32 | 0.0416 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| ensynth_0001_call_clean | clean | 166.8 | 2/2 | 98.98 | 8.39 | 8.88 | 8.28 | 8.21 | 0.0286 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| ensynth_0003_call_overlap | overlap | 216.8 | 2/3 | 99.38 | 8.53 | 36.22 | 20.96 | 26.74 | 0.0221 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| ensynth_0004_meeting_clean | clean | 158.6 | 3/4 | 97.63 | 2.10 | 20.00 | 14.45 | 20.41 | 0.0219 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| ensynth_0005_call_noise | noise | 125.5 | 2/2 | 97.54 | 20.00 | 35.42 | 20.44 | 26.48 | 0.0199 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| ensynth_0008_meeting_farfield | farfield | 103.1 | 2/2 | 98.90 | 12.68 | 27.46 | 16.11 | 23.38 | 0.02 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| ensynth_0009_call_phone | phone | 138.9 | 2/1 | 99.55 | 8.37 | 96.03 | 54.01 | 75.55 | 0.0184 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| ensynth_0011_call_clean | clean | 134.4 | 3/3 | 99.09 | 6.32 | 19.86 | 10.45 | 15.59 | 0.0219 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| ensynth_0012_meeting_farfield | farfield | 232.5 | 2/6 | 97.79 | 13.53 | 88.23 | 50.31 | 47.82 | 0.0194 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| ensynth_0013_call_phone | phone | 176.0 | 3/2 | 97.36 | 23.49 | 44.63 | 19.14 | 46.46 | 0.0188 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| mix_0000_meeting_noise | noise | 103.8 | 3/4 | 89.26 | 48.35 | 52.89 | 45.87 | 54.01 | 0.0283 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| mix_0002_meeting_clean | clean | 125.6 | 4/4 | 80.21 | 24.91 | 40.83 | 21.66 | 21.60 | 0.026 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| mix_0003_call_phone | phone | 122.1 | 3/1 | 86.07 | 68.80 | 90.25 | 83.16 | 92.09 | 0.0226 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |
| mix_0004_meeting_farfield | farfield | 74.5 | 4/4 | 96.27 | 39.13 | 70.19 | 46.78 | 62.91 | 0.0217 | experiment: baseline + vad.rescue(snr10,floor-50); ASR/VAD times only (diarization taken from cache, RTF not comparable) |

## refpipe-medium-norelabel-dev

tier=medium, platform=desktop, device=Darwin arm64 (arm), threads=3, dataset=refpipe-baseline-dev, config_hash=5a871480829598619a9dd03d269a49fca5a5d2cdf67d5068009c32e03468589b

### По условиям

| group | files | WER raw | WER norm | WER punct | WER p-sep | WER p-lc | WER num | cpWER | DER | DER ±.25 | JER | spk acc | ts start MAE ms | ts end MAE ms | start<300ms | RTF |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ALL | 84 | 56.41 | 22.60 | 55.86 | 51.51 | 31.06 | 22.10 | 54.92 | 36.06 | 28.25 | 54.07 | 29.76 | 315 | 427 | 71.74 | 0.040 |
| clean | 17 | 57.53 | 10.69 | 57.06 | 53.49 | 20.97 | 10.23 | 26.42 | 17.51 | 9.46 | 35.16 | 41.18 | 205 | 282 | 81.67 | 0.039 |
| farfield | 12 | 63.32 | 26.43 | 62.97 | 59.54 | 34.67 | 25.87 | 81.51 | 52.83 | 45.00 | 65.41 | 8.33 | 363 | 463 | 68.06 | 0.035 |
| noise | 14 | 47.07 | 20.53 | 46.12 | 41.03 | 28.92 | 20.12 | 40.76 | 25.14 | 17.72 | 35.55 | 42.86 | 226 | 349 | 74.16 | 0.041 |
| overlap | 11 | 59.18 | 19.73 | 58.71 | 55.08 | 30.48 | 19.62 | 39.88 | 25.77 | 15.77 | 52.51 | 27.27 | 294 | 433 | 65.11 | 0.042 |
| phone | 22 | 61.34 | 34.41 | 60.95 | 55.89 | 40.35 | 33.82 | 75.89 | 50.23 | 44.08 | 71.28 | 27.27 | 477 | 607 | 64.80 | 0.041 |
| reverb | 8 | 32.19 | 16.19 | 31.23 | 27.14 | 23.81 | 14.94 | 72.00 | 48.18 | 37.94 | 64.51 | 25.00 | 235 | 351 | 80.14 | 0.042 |

### По файлам

| file | condition | dur s | spk ref/hyp | WER raw | WER norm | cpWER | DER | JER | RTF | notes |
|---|---|---|---|---|---|---|---|---|---|---|
| synth_0000_meeting_overlap | overlap | 278.5 | 2/2 | 39.93 | 18.26 | 20.94 | 11.56 | 17.03 | 0.0585 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0001_call_phone | phone | 217.5 | 2/4 | 52.45 | 35.64 | 48.36 | 40.73 | 55.60 | 0.0588 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0002_meeting_farfield | farfield | 161.6 | 3/5 | 65.62 | 54.01 | 55.75 | 50.64 | 57.54 | 0.0435 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0003_call_phone | phone | 268.7 | 2/1 | 54.74 | 38.45 | 58.33 | 29.32 | 61.86 | 0.0645 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0004_meeting_clean | clean | 150.5 | 2/3 | 26.17 | 7.60 | 9.81 | 5.07 | 5.00 | 0.0728 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0006_meeting_clean | clean | 109.2 | 4/5 | 24.90 | 8.05 | 15.25 | 13.78 | 19.89 | 0.0434 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0007_call_reverb | reverb | 71.4 | 3/3 | 48.67 | 34.54 | 66.36 | 45.45 | 49.21 | 0.0481 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0010_meeting_noise | noise | 182.7 | 2/3 | 47.32 | 23.76 | 37.86 | 21.30 | 25.56 | 0.0461 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0011_call_phone | phone | 109.0 | 3/3 | 28.91 | 9.76 | 42.44 | 34.02 | 41.55 | 0.042 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0012_meeting_noise | noise | 261.4 | 3/7 | 39.73 | 18.68 | 33.33 | 19.52 | 18.56 | 0.0436 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0013_call_clean | clean | 143.3 | 2/4 | 27.80 | 9.38 | 14.93 | 18.34 | 16.43 | 0.0516 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0014_meeting_farfield | farfield | 152.2 | 2/3 | 42.17 | 17.58 | 80.30 | 46.46 | 54.47 | 0.0425 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0015_call_overlap | overlap | 208.8 | 4/5 | 47.74 | 25.76 | 36.27 | 19.31 | 28.64 | 0.0459 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0016_meeting_farfield | farfield | 249.0 | 2/3 | 42.39 | 23.45 | 53.10 | 28.35 | 53.65 | 0.0442 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0017_call_phone | phone | 75.9 | 4/1 | 63.08 | 53.57 | 124.21 | 75.59 | 92.46 | 0.0345 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0018_meeting_noise | noise | 245.8 | 4/5 | 35.65 | 12.40 | 56.87 | 33.89 | 32.65 | 0.0433 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0019_call_noise | noise | 91.3 | 2/4 | 32.81 | 13.73 | 25.49 | 20.99 | 21.44 | 0.0434 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0023_call_phone | phone | 178.7 | 4/4 | 69.28 | 57.73 | 76.36 | 66.78 | 76.65 | 0.0435 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0024_meeting_reverb | reverb | 249.3 | 2/3 | 33.84 | 12.98 | 64.15 | 38.47 | 49.01 | 0.0464 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0025_call_noise | noise | 255.5 | 2/5 | 38.32 | 19.38 | 30.55 | 13.58 | 16.37 | 0.0455 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0026_meeting_reverb | reverb | 73.1 | 4/5 | 38.89 | 22.13 | 47.54 | 41.34 | 43.49 | 0.0395 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0027_call_clean | clean | 243.2 | 2/2 | 39.62 | 12.64 | 12.97 | 6.02 | 6.12 | 0.0444 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0028_meeting_farfield | farfield | 229.3 | 2/3 | 63.03 | 52.74 | 85.20 | 57.44 | 61.12 | 0.0322 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0029_call_phone | phone | 82.3 | 2/2 | 47.46 | 35.84 | 110.41 | 52.46 | 73.49 | 0.0357 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0031_call_phone | phone | 119.4 | 2/3 | 52.77 | 38.61 | 67.57 | 52.38 | 63.17 | 0.0328 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0034_meeting_clean | clean | 191.1 | 4/4 | 31.88 | 8.64 | 14.66 | 9.50 | 12.97 | 0.0375 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0035_call_overlap | overlap | 122.8 | 3/4 | 28.41 | 15.12 | 39.53 | 33.91 | 35.47 | 0.0353 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c01-c02_v1_call_phone | phone | 87.8 | 3/2 | 32.57 | 21.10 | 101.84 | 53.14 | 72.73 | 0.0381 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c01_v0_meeting_clean | clean | 58.6 | 3/3 | 21.10 | 11.01 | 22.94 | 17.83 | 28.92 | 0.0377 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c02-c06_v1_meeting_farfield | farfield | 93.5 | 3/4 | 27.60 | 17.71 | 111.98 | 70.45 | 83.86 | 0.0362 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c02_v0_call_phone | phone | 41.4 | 3/1 | 35.78 | 27.52 | 98.17 | 56.76 | 82.74 | 0.0373 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c06-c09_v1_meeting_farfield | farfield | 88.4 | 3/4 | 25.97 | 15.00 | 73.33 | 56.30 | 63.49 | 0.0344 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c06_v0_call_noise | noise | 31.9 | 3/3 | 32.53 | 19.28 | 39.76 | 29.84 | 36.37 | 0.0367 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c07-c08_v1_call_phone | phone | 45.7 | 2/2 | 27.43 | 19.47 | 41.59 | 34.16 | 40.16 | 0.0357 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c07_v0_meeting_reverb | reverb | 22.7 | 2/2 | 43.59 | 28.21 | 43.59 | 71.36 | 56.40 | 0.0331 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c08-c10_v1_meeting_reverb | reverb | 66.2 | 2/1 | 22.90 | 14.62 | 100.77 | 67.63 | 76.98 | 0.0359 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c08_v0_call_reverb | reverb | 29.1 | 2/1 | 24.32 | 16.22 | 97.30 | 61.51 | 75.17 | 0.0377 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c09-c11_v1_call_phone | phone | 61.7 | 3/1 | 34.38 | 20.75 | 123.94 | 75.77 | 88.74 | 0.035 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c09_v0_meeting_overlap | overlap | 30.1 | 3/3 | 42.86 | 36.08 | 84.54 | 64.58 | 69.95 | 0.0375 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c10-c12_v1_meeting_noise | noise | 55.9 | 2/2 | 42.02 | 28.81 | 44.91 | 37.21 | 38.48 | 0.0391 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c10_v0_call_clean | clean | 25.6 | 2/1 | 29.83 | 12.50 | 103.57 | 60.14 | 76.00 | 0.0341 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c11-c13_v1_call_clean | clean | 57.8 | 3/3 | 28.99 | 16.36 | 66.67 | 34.36 | 54.82 | 0.0362 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c11_v0_meeting_noise | noise | 44.0 | 3/2 | 24.47 | 10.99 | 64.83 | 40.32 | 55.68 | 0.0383 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c12_v0_call_phone | phone | 18.9 | 2/1 | 33.87 | 20.97 | 70.97 | 54.52 | 71.32 | 0.0348 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c13-c16_v1_call_clean | clean | 45.1 | 3/5 | 37.61 | 22.41 | 75.00 | 62.63 | 66.19 | 0.035 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c13_v0_meeting_reverb | reverb | 40.8 | 3/1 | 17.33 | 5.41 | 104.05 | 68.39 | 85.22 | 0.0352 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c14-c23_v1_meeting_farfield | farfield | 84.4 | 4/3 | 33.53 | 23.95 | 84.43 | 72.55 | 79.81 | 0.0366 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c14_v0_call_overlap | overlap | 26.5 | 4/3 | 71.08 | 55.70 | 67.09 | 59.99 | 71.86 | 0.0366 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c16-c18_v1_meeting_clean | clean | 59.8 | 3/4 | 20.00 | 6.36 | 50.91 | 53.68 | 56.08 | 0.0382 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c16_v0_call_clean | clean | 20.4 | 3/2 | 57.14 | 23.81 | 100.00 | 59.30 | 72.63 | 0.0341 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c17-c20_v1_call_phone | phone | 44.3 | 2/2 | 27.78 | 15.87 | 67.46 | 47.28 | 59.42 | 0.0371 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c17_v0_meeting_reverb | reverb | 28.7 | 2/1 | 11.32 | 5.66 | 86.79 | 80.40 | 80.64 | 0.0379 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c18-c19_v1_meeting_farfield | farfield | 66.6 | 3/4 | 47.79 | 36.50 | 93.43 | 83.13 | 81.46 | 0.0338 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c18_v0_call_phone | phone | 24.9 | 3/1 | 23.53 | 11.77 | 116.18 | 78.30 | 88.04 | 0.0327 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c19-c21_v1_call_phone | phone | 59.9 | 3/5 | 82.47 | 73.55 | 85.81 | 65.44 | 68.27 | 0.0386 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c19_v0_meeting_overlap | overlap | 26.1 | 3/2 | 47.06 | 36.23 | 76.81 | 52.93 | 69.23 | 0.0359 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c20-c22_v1_meeting_noise | noise | 69.2 | 2/2 | 20.61 | 7.63 | 39.70 | 34.07 | 41.39 | 0.0341 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c20_v0_call_phone | phone | 26.9 | 2/1 | 20.55 | 8.22 | 82.19 | 65.66 | 77.26 | 0.0385 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c21_v0_meeting_phone | phone | 40.3 | 3/2 | 27.91 | 18.61 | 97.67 | 57.94 | 77.87 | 0.0339 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c22_v0_call_clean | clean | 16.1 | 2/2 | 37.93 | 25.86 | 60.35 | 48.41 | 54.79 | 0.0358 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c23_v0_meeting_clean | clean | 42.9 | 4/4 | 24.14 | 18.18 | 38.64 | 22.36 | 43.66 | 0.0346 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c25_v0_meeting_noise | noise | 48.5 | 2/2 | 30.95 | 18.07 | 18.07 | 9.94 | 9.87 | 0.0357 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c26_v0_call_phone | phone | 25.5 | 3/2 | 25.00 | 9.38 | 115.62 | 68.92 | 84.59 | 0.0326 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c28_v0_call_noise | noise | 23.7 | 3/4 | 33.33 | 17.74 | 62.90 | 37.33 | 61.03 | 0.0338 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c29_v0_meeting_farfield | farfield | 26.0 | 2/2 | 33.90 | 20.34 | 61.02 | 67.64 | 74.43 | 0.0332 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c32_v0_call_noise | noise | 19.3 | 2/2 | 47.06 | 41.18 | 49.02 | 45.91 | 59.94 | 0.0352 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c34_v0_call_overlap | overlap | 30.5 | 2/2 | 35.13 | 12.16 | 33.78 | 22.83 | 28.77 | 0.0359 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c36_v0_call_phone | phone | 26.1 | 2/1 | 49.18 | 38.33 | 70.00 | 60.72 | 75.56 | 0.0359 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c37_v0_meeting_overlap | overlap | 25.2 | 3/2 | 21.88 | 17.19 | 34.38 | 34.09 | 73.47 | 0.0335 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c39_v0_meeting_overlap | overlap | 26.0 | 4/2 | 58.46 | 38.46 | 80.00 | 61.94 | 75.83 | 0.0338 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c40_v0_call_overlap | overlap | 21.5 | 3/4 | 58.46 | 43.94 | 81.82 | 71.98 | 77.53 | 0.0345 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| ensynth_0001_call_clean | clean | 166.8 | 2/6 | 99.15 | 7.07 | 11.35 | 9.59 | 9.34 | 0.0267 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| ensynth_0003_call_overlap | overlap | 216.8 | 2/5 | 99.38 | 8.40 | 39.80 | 22.80 | 29.82 | 0.0255 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| ensynth_0004_meeting_clean | clean | 158.6 | 3/4 | 97.63 | 2.32 | 28.84 | 17.52 | 24.61 | 0.0245 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| ensynth_0005_call_noise | noise | 125.5 | 2/4 | 97.30 | 18.55 | 42.89 | 22.81 | 30.91 | 0.0238 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| ensynth_0008_meeting_farfield | farfield | 103.1 | 2/6 | 98.16 | 13.38 | 108.80 | 58.20 | 60.14 | 0.0247 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| ensynth_0009_call_phone | phone | 138.9 | 2/1 | 99.55 | 5.51 | 93.61 | 54.01 | 75.55 | 0.0228 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| ensynth_0011_call_clean | clean | 134.4 | 3/6 | 99.09 | 6.09 | 28.67 | 15.95 | 23.46 | 0.0239 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| ensynth_0012_meeting_farfield | farfield | 232.5 | 2/5 | 97.64 | 12.50 | 98.38 | 59.41 | 56.68 | 0.0238 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| ensynth_0013_call_phone | phone | 176.0 | 3/3 | 97.19 | 26.01 | 51.17 | 25.02 | 52.10 | 0.0239 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| mix_0000_meeting_noise | noise | 103.8 | 3/3 | 83.47 | 54.96 | 58.26 | 44.07 | 49.45 | 0.0386 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| mix_0002_meeting_clean | clean | 125.6 | 4/4 | 83.33 | 30.45 | 47.06 | 26.52 | 26.85 | 0.0321 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| mix_0003_call_phone | phone | 122.1 | 3/2 | 93.44 | 74.93 | 84.12 | 82.21 | 89.03 | 0.0265 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| mix_0004_meeting_farfield | farfield | 74.5 | 4/5 | 100.00 | 36.65 | 47.83 | 39.06 | 58.29 | 0.026 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |

## refpipe-medium-relabel

tier=medium, platform=desktop, device=Darwin arm64 (arm), threads=3, dataset=refpipe-baseline-dev, config_hash=b44dedf419241f334221997098e2f7c00d2198391b63cc49092f5efe11a6696d

### По условиям

| group | files | WER raw | WER norm | WER punct | WER p-sep | WER p-lc | WER num | cpWER | DER | DER ±.25 | JER | spk acc | ts start MAE ms | ts end MAE ms | start<300ms | RTF |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ALL | 84 | 56.41 | 22.60 | 55.86 | 51.51 | 31.06 | 22.10 | 32.14 | 23.11 | 15.97 | 34.72 | 66.67 | 315 | 427 | 71.74 | 0.071 |
| clean | 17 | 57.53 | 10.69 | 57.06 | 53.49 | 20.97 | 10.23 | 17.19 | 14.16 | 6.86 | 28.56 | 64.71 | 205 | 282 | 81.67 | 0.068 |
| farfield | 12 | 63.32 | 26.43 | 62.97 | 59.54 | 34.67 | 25.87 | 31.81 | 24.00 | 17.82 | 33.14 | 58.33 | 363 | 463 | 68.06 | 0.061 |
| noise | 14 | 47.07 | 20.53 | 46.12 | 41.03 | 28.92 | 20.12 | 30.47 | 19.87 | 13.42 | 27.10 | 78.57 | 226 | 349 | 74.16 | 0.074 |
| overlap | 11 | 59.18 | 19.73 | 58.71 | 55.08 | 30.48 | 19.62 | 31.83 | 24.06 | 14.85 | 47.01 | 45.45 | 294 | 433 | 65.11 | 0.072 |
| phone | 22 | 61.34 | 34.41 | 60.95 | 55.89 | 40.35 | 33.82 | 48.66 | 33.53 | 26.58 | 43.75 | 68.18 | 477 | 607 | 64.80 | 0.082 |
| reverb | 8 | 32.19 | 16.19 | 31.23 | 27.14 | 23.81 | 14.94 | 20.30 | 17.59 | 10.89 | 21.76 | 87.50 | 235 | 351 | 80.14 | 0.062 |

### По файлам

| file | condition | dur s | spk ref/hyp | WER raw | WER norm | cpWER | DER | JER | RTF | notes |
|---|---|---|---|---|---|---|---|---|---|---|
| synth_0000_meeting_overlap | overlap | 278.5 | 2/2 | 39.93 | 18.26 | 22.11 | 13.83 | 20.36 | 0.076 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0001_call_phone | phone | 217.5 | 2/2 | 52.45 | 35.64 | 36.03 | 33.47 | 40.99 | 0.1373 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0002_meeting_farfield | farfield | 161.6 | 3/2 | 65.62 | 54.01 | 54.35 | 46.85 | 47.14 | 0.046 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0003_call_phone | phone | 268.7 | 2/2 | 54.74 | 38.45 | 38.60 | 13.46 | 19.84 | 0.1477 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0004_meeting_clean | clean | 150.5 | 2/2 | 26.17 | 7.60 | 11.08 | 6.29 | 6.47 | 0.1621 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0006_meeting_clean | clean | 109.2 | 4/3 | 24.90 | 8.05 | 18.22 | 15.89 | 35.34 | 0.0853 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0007_call_reverb | reverb | 71.4 | 3/3 | 48.67 | 34.54 | 40.91 | 35.07 | 25.45 | 0.0743 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0010_meeting_noise | noise | 182.7 | 2/2 | 47.32 | 23.76 | 28.46 | 17.90 | 17.58 | 0.0866 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0011_call_phone | phone | 109.0 | 3/3 | 28.91 | 9.76 | 17.56 | 21.86 | 20.32 | 0.0755 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0012_meeting_noise | noise | 261.4 | 3/2 | 39.73 | 18.68 | 64.86 | 33.73 | 49.91 | 0.0731 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0013_call_clean | clean | 143.3 | 2/2 | 27.80 | 9.38 | 13.89 | 17.09 | 16.89 | 0.0728 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0014_meeting_farfield | farfield | 152.2 | 2/2 | 42.17 | 17.58 | 20.61 | 11.54 | 12.73 | 0.0939 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0015_call_overlap | overlap | 208.8 | 4/2 | 47.74 | 25.76 | 54.07 | 30.35 | 63.70 | 0.1173 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0016_meeting_farfield | farfield | 249.0 | 2/2 | 42.39 | 23.45 | 29.43 | 12.81 | 12.44 | 0.0839 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0017_call_phone | phone | 75.9 | 4/1 | 63.08 | 53.57 | 124.21 | 75.59 | 92.46 | 0.1013 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0018_meeting_noise | noise | 245.8 | 4/4 | 35.65 | 12.40 | 18.51 | 10.79 | 10.85 | 0.0987 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0019_call_noise | noise | 91.3 | 2/2 | 32.81 | 13.73 | 13.73 | 17.80 | 16.45 | 0.0678 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0023_call_phone | phone | 178.7 | 4/3 | 69.28 | 57.73 | 60.91 | 50.94 | 60.20 | 0.0513 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0024_meeting_reverb | reverb | 249.3 | 2/2 | 33.84 | 12.98 | 13.37 | 8.40 | 8.91 | 0.0628 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0025_call_noise | noise | 255.5 | 2/2 | 38.32 | 19.38 | 20.65 | 8.63 | 11.77 | 0.0589 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0026_meeting_reverb | reverb | 73.1 | 4/4 | 38.89 | 22.13 | 22.13 | 23.16 | 10.92 | 0.0519 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0027_call_clean | clean | 243.2 | 2/2 | 39.62 | 12.64 | 14.26 | 7.37 | 7.37 | 0.0568 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0028_meeting_farfield | farfield | 229.3 | 2/2 | 63.03 | 52.74 | 56.80 | 36.89 | 33.91 | 0.0437 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0029_call_phone | phone | 82.3 | 2/2 | 47.46 | 35.84 | 36.99 | 20.36 | 18.92 | 0.0501 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0031_call_phone | phone | 119.4 | 2/2 | 52.77 | 38.61 | 39.00 | 33.55 | 33.52 | 0.0447 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0034_meeting_clean | clean | 191.1 | 4/4 | 31.88 | 8.64 | 13.35 | 11.37 | 12.40 | 0.059 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0035_call_overlap | overlap | 122.8 | 3/3 | 28.41 | 15.12 | 17.83 | 25.19 | 19.55 | 0.0618 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c01-c02_v1_call_phone | phone | 87.8 | 3/1 | 32.57 | 21.10 | 110.09 | 58.83 | 84.10 | 0.0769 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c01_v0_meeting_clean | clean | 58.6 | 3/3 | 21.10 | 11.01 | 11.01 | 10.22 | 12.25 | 0.0644 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c02-c06_v1_meeting_farfield | farfield | 93.5 | 3/3 | 27.60 | 17.71 | 23.96 | 16.22 | 17.58 | 0.0618 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c02_v0_call_phone | phone | 41.4 | 3/3 | 35.78 | 27.52 | 27.52 | 21.73 | 26.17 | 0.0799 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c06-c09_v1_meeting_farfield | farfield | 88.4 | 3/3 | 25.97 | 15.00 | 20.00 | 23.23 | 24.04 | 0.0651 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c06_v0_call_noise | noise | 31.9 | 3/3 | 32.53 | 19.28 | 34.94 | 21.94 | 25.79 | 0.0754 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c07-c08_v1_call_phone | phone | 45.7 | 2/2 | 27.43 | 19.47 | 28.32 | 26.88 | 28.47 | 0.0721 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c07_v0_meeting_reverb | reverb | 22.7 | 2/6 | 43.59 | 28.21 | 97.44 | 73.79 | 62.60 | 0.0593 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c08-c10_v1_meeting_reverb | reverb | 66.2 | 2/2 | 22.90 | 14.62 | 18.46 | 21.13 | 22.05 | 0.065 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c08_v0_call_reverb | reverb | 29.1 | 2/2 | 24.32 | 16.22 | 22.97 | 18.09 | 17.95 | 0.0653 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c09-c11_v1_call_phone | phone | 61.7 | 3/3 | 34.38 | 20.75 | 47.34 | 31.94 | 36.69 | 0.0698 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c09_v0_meeting_overlap | overlap | 30.1 | 3/4 | 42.86 | 36.08 | 65.98 | 55.58 | 56.64 | 0.0666 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c10-c12_v1_meeting_noise | noise | 55.9 | 2/2 | 42.02 | 28.81 | 34.75 | 31.44 | 31.06 | 0.0838 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c10_v0_call_clean | clean | 25.6 | 2/1 | 29.83 | 12.50 | 103.57 | 60.14 | 76.00 | 0.0698 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c11-c13_v1_call_clean | clean | 57.8 | 3/3 | 28.99 | 16.36 | 29.70 | 18.81 | 24.05 | 0.068 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c11_v0_meeting_noise | noise | 44.0 | 3/3 | 24.47 | 10.99 | 20.88 | 14.32 | 15.18 | 0.0648 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c12_v0_call_phone | phone | 18.9 | 2/1 | 33.87 | 20.97 | 70.97 | 54.52 | 71.32 | 0.0606 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c13-c16_v1_call_clean | clean | 45.1 | 3/3 | 37.61 | 22.41 | 27.59 | 25.51 | 27.73 | 0.0565 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c13_v0_meeting_reverb | reverb | 40.8 | 3/3 | 17.33 | 5.41 | 5.41 | 10.89 | 11.81 | 0.0536 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c14-c23_v1_meeting_farfield | farfield | 84.4 | 4/3 | 33.53 | 23.95 | 46.71 | 28.43 | 42.61 | 0.059 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c14_v0_call_overlap | overlap | 26.5 | 4/2 | 71.08 | 55.70 | 75.95 | 67.39 | 76.16 | 0.059 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c16-c18_v1_meeting_clean | clean | 59.8 | 3/2 | 20.00 | 6.36 | 55.45 | 53.81 | 58.42 | 0.0668 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c16_v0_call_clean | clean | 20.4 | 3/2 | 57.14 | 23.81 | 66.67 | 47.01 | 55.56 | 0.0658 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c17-c20_v1_call_phone | phone | 44.3 | 2/2 | 27.78 | 15.87 | 27.78 | 23.77 | 27.92 | 0.0611 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c17_v0_meeting_reverb | reverb | 28.7 | 2/2 | 11.32 | 5.66 | 5.66 | 14.52 | 14.39 | 0.0578 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c18-c19_v1_meeting_farfield | farfield | 66.6 | 3/2 | 47.79 | 36.50 | 56.20 | 47.19 | 53.45 | 0.0576 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c18_v0_call_phone | phone | 24.9 | 3/2 | 23.53 | 11.77 | 58.82 | 46.94 | 56.20 | 0.0658 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c19-c21_v1_call_phone | phone | 59.9 | 3/3 | 82.47 | 73.55 | 77.42 | 61.52 | 62.17 | 0.0579 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c19_v0_meeting_overlap | overlap | 26.1 | 3/2 | 47.06 | 36.23 | 75.36 | 49.37 | 66.86 | 0.0608 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c20-c22_v1_meeting_noise | noise | 69.2 | 2/2 | 20.61 | 7.63 | 10.69 | 19.79 | 19.75 | 0.0608 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c20_v0_call_phone | phone | 26.9 | 2/1 | 20.55 | 8.22 | 82.19 | 65.66 | 77.26 | 0.07 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c21_v0_meeting_phone | phone | 40.3 | 3/3 | 27.91 | 18.61 | 22.09 | 16.76 | 18.10 | 0.0607 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c22_v0_call_clean | clean | 16.1 | 2/2 | 37.93 | 25.86 | 37.93 | 35.09 | 38.56 | 0.0533 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c23_v0_meeting_clean | clean | 42.9 | 4/3 | 24.14 | 18.18 | 44.32 | 25.78 | 48.21 | 0.0506 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c25_v0_meeting_noise | noise | 48.5 | 2/2 | 30.95 | 18.07 | 18.07 | 9.94 | 9.87 | 0.0491 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c26_v0_call_phone | phone | 25.5 | 3/3 | 25.00 | 9.38 | 12.50 | 14.08 | 14.43 | 0.0475 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c28_v0_call_noise | noise | 23.7 | 3/2 | 33.33 | 17.74 | 56.45 | 32.93 | 58.61 | 0.0505 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c29_v0_meeting_farfield | farfield | 26.0 | 2/1 | 33.90 | 20.34 | 66.10 | 63.43 | 73.60 | 0.0466 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c32_v0_call_noise | noise | 19.3 | 2/2 | 47.06 | 41.18 | 41.18 | 36.43 | 48.26 | 0.0624 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c34_v0_call_overlap | overlap | 30.5 | 2/2 | 35.13 | 12.16 | 24.32 | 18.30 | 22.67 | 0.0532 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c36_v0_call_phone | phone | 26.1 | 2/2 | 49.18 | 38.33 | 50.00 | 49.60 | 53.12 | 0.0554 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c37_v0_meeting_overlap | overlap | 25.2 | 3/1 | 21.88 | 17.19 | 31.25 | 33.27 | 74.78 | 0.061 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c39_v0_meeting_overlap | overlap | 26.0 | 4/3 | 58.46 | 38.46 | 47.69 | 51.11 | 58.57 | 0.0505 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c40_v0_call_overlap | overlap | 21.5 | 3/3 | 58.46 | 43.94 | 51.52 | 44.39 | 45.74 | 0.0542 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| ensynth_0001_call_clean | clean | 166.8 | 2/2 | 99.15 | 7.07 | 7.73 | 7.94 | 7.80 | 0.0435 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| ensynth_0003_call_overlap | overlap | 216.8 | 2/2 | 99.38 | 8.40 | 12.98 | 10.82 | 12.09 | 0.0419 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| ensynth_0004_meeting_clean | clean | 158.6 | 3/3 | 97.63 | 2.32 | 2.32 | 6.62 | 6.60 | 0.0446 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| ensynth_0005_call_noise | noise | 125.5 | 2/2 | 97.30 | 18.55 | 22.41 | 14.45 | 15.81 | 0.0452 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| ensynth_0008_meeting_farfield | farfield | 103.1 | 2/2 | 98.16 | 13.38 | 13.38 | 9.46 | 10.94 | 0.0422 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| ensynth_0009_call_phone | phone | 138.9 | 2/2 | 99.55 | 5.51 | 9.03 | 10.52 | 12.20 | 0.0489 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| ensynth_0011_call_clean | clean | 134.4 | 3/3 | 99.09 | 6.09 | 8.13 | 7.88 | 9.25 | 0.0421 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| ensynth_0012_meeting_farfield | farfield | 232.5 | 2/2 | 97.64 | 12.50 | 12.50 | 10.35 | 10.28 | 0.0594 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| ensynth_0013_call_phone | phone | 176.0 | 3/2 | 97.19 | 26.01 | 45.13 | 20.17 | 46.86 | 0.0619 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| mix_0000_meeting_noise | noise | 103.8 | 3/2 | 83.47 | 54.96 | 54.96 | 42.60 | 48.50 | 0.094 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| mix_0002_meeting_clean | clean | 125.6 | 4/3 | 83.33 | 30.45 | 35.99 | 21.99 | 42.68 | 0.074 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| mix_0003_call_phone | phone | 122.1 | 3/3 | 93.44 | 74.93 | 75.49 | 63.40 | 61.32 | 0.0586 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| mix_0004_meeting_farfield | farfield | 74.5 | 4/2 | 100.00 | 36.65 | 44.10 | 34.25 | 58.90 | 0.0428 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |

## refpipe-medium-norelabel-test

tier=medium, platform=desktop, device=Darwin arm64 (arm), threads=3, dataset=refpipe-baseline-test, config_hash=5a871480829598619a9dd03d269a49fca5a5d2cdf67d5068009c32e03468589b

### По условиям

| group | files | WER raw | WER norm | WER punct | WER p-sep | WER p-lc | WER num | cpWER | DER | DER ±.25 | JER | spk acc | ts start MAE ms | ts end MAE ms | start<300ms | RTF |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ALL | 31 | 54.46 | 21.74 | 53.81 | 48.81 | 29.87 | 20.60 | 49.20 | 32.37 | 23.97 | 48.20 | 41.94 | 268 | 359 | 74.27 | 0.039 |
| clean | 5 | 57.26 | 19.58 | 56.77 | 52.84 | 27.70 | 19.39 | 32.22 | 20.40 | 11.82 | 32.96 | 60.00 | 223 | 258 | 75.38 | 0.042 |
| farfield | 4 | 32.68 | 15.06 | 31.69 | 26.12 | 21.30 | 12.63 | 59.40 | 37.71 | 29.69 | 60.82 | 25.00 | 192 | 270 | 82.25 | 0.044 |
| noise | 7 | 63.94 | 14.73 | 63.21 | 60.79 | 24.67 | 13.57 | 26.89 | 17.48 | 8.43 | 30.43 | 71.43 | 177 | 236 | 77.62 | 0.037 |
| overlap | 6 | 61.03 | 26.59 | 60.05 | 54.47 | 35.28 | 26.54 | 48.10 | 32.18 | 24.90 | 40.80 | 33.33 | 389 | 558 | 64.23 | 0.035 |
| phone | 6 | 39.82 | 23.88 | 39.72 | 33.54 | 30.31 | 20.92 | 81.02 | 47.62 | 37.43 | 68.63 | 16.67 | 218 | 315 | 74.62 | 0.037 |
| reverb | 3 | 49.74 | 35.69 | 49.40 | 44.04 | 41.37 | 32.93 | 97.07 | 62.42 | 55.26 | 72.16 | 33.33 | 463 | 558 | 73.36 | 0.041 |

### По файлам

| file | condition | dur s | spk ref/hyp | WER raw | WER norm | cpWER | DER | JER | RTF | notes |
|---|---|---|---|---|---|---|---|---|---|---|
| synth_0005_call_clean | clean | 227.3 | 3/3 | 35.86 | 11.19 | 19.18 | 8.82 | 11.63 | 0.055 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0008_meeting_reverb | reverb | 201.6 | 4/3 | 49.89 | 33.48 | 99.78 | 63.61 | 79.11 | 0.0426 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0009_call_phone | phone | 86.4 | 2/2 | 47.31 | 31.48 | 41.48 | 21.63 | 26.52 | 0.0421 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0020_meeting_overlap | overlap | 114.0 | 4/5 | 33.97 | 13.73 | 47.06 | 28.16 | 44.18 | 0.0451 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0021_call_noise | noise | 213.6 | 2/2 | 32.19 | 10.63 | 14.12 | 8.25 | 10.33 | 0.0519 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0022_meeting_farfield | farfield | 209.5 | 2/4 | 32.77 | 11.74 | 48.91 | 29.80 | 36.91 | 0.049 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0030_meeting_overlap | overlap | 173.4 | 4/6 | 49.73 | 29.22 | 62.47 | 39.51 | 40.37 | 0.0367 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0032_meeting_phone | phone | 99.9 | 2/1 | 29.91 | 10.90 | 101.90 | 54.16 | 75.45 | 0.0361 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| synth_0033_call_overlap | overlap | 176.8 | 3/5 | 43.12 | 23.19 | 45.96 | 23.58 | 25.72 | 0.0354 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c03-c05_v1_call_phone | phone | 73.6 | 2/1 | 40.41 | 25.39 | 90.67 | 50.93 | 72.29 | 0.0355 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c03_v0_meeting_farfield | farfield | 53.2 | 2/2 | 45.16 | 27.96 | 78.50 | 46.35 | 60.00 | 0.0356 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c04-c14_v1_meeting_clean | clean | 79.4 | 4/3 | 27.12 | 11.56 | 58.38 | 47.87 | 63.32 | 0.0363 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c04_v0_call_overlap | overlap | 30.0 | 4/3 | 50.00 | 32.98 | 46.81 | 43.61 | 56.61 | 0.0371 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c05-c07_v1_call_noise | noise | 52.3 | 2/2 | 41.01 | 25.90 | 28.06 | 25.29 | 24.38 | 0.0372 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c05_v0_meeting_phone | phone | 44.1 | 2/1 | 27.00 | 10.00 | 94.00 | 63.05 | 77.19 | 0.0346 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c12-c15_v1_meeting_overlap | overlap | 46.1 | 2/2 | 44.96 | 29.46 | 56.59 | 31.95 | 43.05 | 0.0366 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c15-c17_v1_call_phone | phone | 47.4 | 2/1 | 39.17 | 23.33 | 85.00 | 49.33 | 70.62 | 0.0352 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c15_v0_meeting_reverb | reverb | 31.5 | 2/1 | 31.34 | 23.88 | 85.08 | 60.61 | 73.36 | 0.0364 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c24_v0_call_noise | noise | 25.9 | 3/3 | 42.10 | 38.67 | 66.67 | 41.00 | 56.41 | 0.0339 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c27_v0_meeting_noise | noise | 27.8 | 2/2 | 49.15 | 37.29 | 61.02 | 39.52 | 41.83 | 0.0378 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c30_v0_call_phone | phone | 31.4 | 4/1 | 54.65 | 45.35 | 111.63 | 68.89 | 89.73 | 0.0364 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c31_v0_meeting_farfield | farfield | 41.6 | 3/2 | 25.32 | 18.99 | 74.68 | 47.58 | 74.88 | 0.0345 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c33_v0_meeting_clean | clean | 27.7 | 3/3 | 11.47 | 3.28 | 44.26 | 31.73 | 43.92 | 0.0367 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c35_v0_meeting_farfield | farfield | 31.1 | 3/4 | 23.08 | 15.38 | 87.69 | 67.28 | 71.47 | 0.0366 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| cats_c38_v0_call_reverb | reverb | 26.3 | 2/2 | 67.69 | 63.08 | 90.77 | 54.03 | 64.01 | 0.0332 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| ensynth_0000_meeting_clean | clean | 111.6 | 2/4 | 98.10 | 5.88 | 10.70 | 10.61 | 10.30 | 0.0319 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| ensynth_0002_meeting_overlap | overlap | 143.5 | 2/2 | 98.85 | 31.26 | 40.03 | 33.53 | 34.88 | 0.0245 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| ensynth_0006_meeting_noise | noise | 70.2 | 2/4 | 96.65 | 11.85 | 17.06 | 13.41 | 13.13 | 0.0241 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| ensynth_0007_call_noise | noise | 100.5 | 2/4 | 98.85 | 15.79 | 20.78 | 12.13 | 12.09 | 0.0236 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| ensynth_0010_meeting_noise | noise | 69.3 | 3/3 | 97.31 | 4.23 | 62.96 | 37.50 | 54.85 | 0.0217 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |
| mix_0001_call_clean | clean | 127.7 | 3/3 | 69.77 | 47.18 | 55.08 | 33.37 | 35.65 | 0.0336 | control: medium, diarization.relabel disabled (stock sherpa diarization thr 0.5 shift 1.0), 3+3 threads |

## refpipe-medium-relabel-test

tier=medium, platform=desktop, device=Darwin arm64 (arm), threads=3, dataset=refpipe-baseline-test, config_hash=b44dedf419241f334221997098e2f7c00d2198391b63cc49092f5efe11a6696d

### По условиям

| group | files | WER raw | WER norm | WER punct | WER p-sep | WER p-lc | WER num | cpWER | DER | DER ±.25 | JER | spk acc | ts start MAE ms | ts end MAE ms | start<300ms | RTF |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ALL | 31 | 54.46 | 21.74 | 53.81 | 48.81 | 29.87 | 20.60 | 29.85 | 19.79 | 11.69 | 29.64 | 77.42 | 268 | 359 | 74.27 | 0.066 |
| clean | 5 | 57.26 | 19.58 | 56.77 | 52.84 | 27.70 | 19.39 | 21.56 | 13.92 | 7.15 | 17.58 | 100.00 | 223 | 258 | 75.38 | 0.078 |
| farfield | 4 | 32.68 | 15.06 | 31.69 | 26.12 | 21.30 | 12.63 | 21.52 | 12.99 | 6.22 | 24.83 | 75.00 | 192 | 270 | 82.25 | 0.058 |
| noise | 7 | 63.94 | 14.73 | 63.21 | 60.79 | 24.67 | 13.57 | 24.69 | 17.87 | 8.95 | 34.29 | 71.43 | 177 | 236 | 77.62 | 0.055 |
| overlap | 6 | 61.03 | 26.59 | 60.05 | 54.47 | 35.28 | 26.54 | 29.41 | 24.10 | 15.91 | 32.65 | 83.33 | 389 | 558 | 64.23 | 0.058 |
| phone | 6 | 39.82 | 23.88 | 39.72 | 33.54 | 30.31 | 20.92 | 54.29 | 31.41 | 22.24 | 39.74 | 50.00 | 218 | 315 | 74.62 | 0.073 |
| reverb | 3 | 49.74 | 35.69 | 49.40 | 44.04 | 41.37 | 32.93 | 37.59 | 15.03 | 7.79 | 19.03 | 100.00 | 463 | 558 | 73.36 | 0.081 |

### По файлам

| file | condition | dur s | spk ref/hyp | WER raw | WER norm | cpWER | DER | JER | RTF | notes |
|---|---|---|---|---|---|---|---|---|---|---|
| synth_0005_call_clean | clean | 227.3 | 3/3 | 35.86 | 11.19 | 12.08 | 5.92 | 6.05 | 0.0989 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0008_meeting_reverb | reverb | 201.6 | 4/4 | 49.89 | 33.48 | 35.49 | 14.09 | 14.31 | 0.0885 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0009_call_phone | phone | 86.4 | 2/2 | 47.31 | 31.48 | 40.37 | 17.09 | 18.87 | 0.1106 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0020_meeting_overlap | overlap | 114.0 | 4/4 | 33.97 | 13.73 | 18.04 | 11.63 | 20.25 | 0.071 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0021_call_noise | noise | 213.6 | 2/2 | 32.19 | 10.63 | 13.29 | 7.17 | 8.29 | 0.0633 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0022_meeting_farfield | farfield | 209.5 | 2/2 | 32.77 | 11.74 | 15.65 | 8.69 | 9.31 | 0.0597 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0030_meeting_overlap | overlap | 173.4 | 4/4 | 49.73 | 29.22 | 33.51 | 26.31 | 28.52 | 0.0569 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0032_meeting_phone | phone | 99.9 | 2/1 | 29.91 | 10.90 | 101.90 | 54.16 | 75.45 | 0.0512 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| synth_0033_call_overlap | overlap | 176.8 | 3/3 | 43.12 | 23.19 | 25.96 | 15.15 | 20.68 | 0.0617 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c03-c05_v1_call_phone | phone | 73.6 | 2/2 | 40.41 | 25.39 | 29.02 | 18.75 | 19.82 | 0.0698 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c03_v0_meeting_farfield | farfield | 53.2 | 2/2 | 45.16 | 27.96 | 31.18 | 16.36 | 16.86 | 0.0588 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c04-c14_v1_meeting_clean | clean | 79.4 | 4/4 | 27.12 | 11.56 | 15.03 | 15.73 | 15.97 | 0.0716 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c04_v0_call_overlap | overlap | 30.0 | 4/3 | 50.00 | 32.98 | 43.62 | 39.48 | 53.36 | 0.0792 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c05-c07_v1_call_noise | noise | 52.3 | 2/2 | 41.01 | 25.90 | 26.62 | 26.56 | 25.71 | 0.0824 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c05_v0_meeting_phone | phone | 44.1 | 2/2 | 27.00 | 10.00 | 10.00 | 10.57 | 10.54 | 0.0763 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c12-c15_v1_meeting_overlap | overlap | 46.1 | 2/2 | 44.96 | 29.46 | 31.78 | 41.38 | 41.55 | 0.057 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c15-c17_v1_call_phone | phone | 47.4 | 2/1 | 39.17 | 23.33 | 85.00 | 49.33 | 70.62 | 0.0648 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c15_v0_meeting_reverb | reverb | 31.5 | 2/2 | 31.34 | 23.88 | 23.88 | 15.16 | 17.04 | 0.0574 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c24_v0_call_noise | noise | 25.9 | 3/2 | 42.10 | 38.67 | 64.00 | 43.17 | 58.45 | 0.049 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c27_v0_meeting_noise | noise | 27.8 | 2/2 | 49.15 | 37.29 | 86.44 | 75.85 | 74.69 | 0.0513 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c30_v0_call_phone | phone | 31.4 | 4/3 | 54.65 | 45.35 | 46.51 | 30.90 | 43.17 | 0.0506 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c31_v0_meeting_farfield | farfield | 41.6 | 3/2 | 25.32 | 18.99 | 45.57 | 28.32 | 52.51 | 0.0523 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c33_v0_meeting_clean | clean | 27.7 | 3/3 | 11.47 | 3.28 | 27.87 | 23.54 | 29.32 | 0.0571 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c35_v0_meeting_farfield | farfield | 31.1 | 3/3 | 23.08 | 15.38 | 20.00 | 17.34 | 20.65 | 0.0565 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| cats_c38_v0_call_reverb | reverb | 26.3 | 2/2 | 67.69 | 63.08 | 66.15 | 23.43 | 25.74 | 0.0512 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| ensynth_0000_meeting_clean | clean | 111.6 | 2/2 | 98.10 | 5.88 | 5.88 | 9.04 | 9.16 | 0.0473 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| ensynth_0002_meeting_overlap | overlap | 143.5 | 2/2 | 98.85 | 31.26 | 31.58 | 31.78 | 31.55 | 0.0401 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| ensynth_0006_meeting_noise | noise | 70.2 | 2/2 | 96.65 | 11.85 | 12.32 | 12.15 | 11.68 | 0.0429 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| ensynth_0007_call_noise | noise | 100.5 | 2/2 | 98.85 | 15.79 | 16.07 | 10.74 | 11.07 | 0.0426 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| ensynth_0010_meeting_noise | noise | 69.3 | 3/2 | 97.31 | 4.23 | 55.03 | 33.89 | 50.15 | 0.0402 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |
| mix_0001_call_clean | clean | 127.7 | 3/3 | 69.77 | 47.18 | 48.53 | 30.24 | 27.41 | 0.0773 | medium + diarization.relabel (k1_silhouette_thr=0.35, k1_require_stock_single), 3+3 threads, no cache, no oracle speakers |

## refpipe-bp-robust-dev-bp0.0

tier=medium, platform=desktop, device=Darwin arm64 (arm), threads=2, dataset=refpipe-baseline-dev, config_hash=687e156f3354e6d9398fd837dcb195df0b214e2876cd67e705dfd31a733b9b33

### По условиям

| group | files | WER raw | WER norm | WER punct | WER p-sep | WER p-lc | WER num | cpWER | DER | DER ±.25 | JER | spk acc | ts start MAE ms | ts end MAE ms | start<300ms | RTF |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ALL | 84 | 56.41 | 22.60 | 55.86 | 51.51 | 31.06 | 22.10 | 32.14 | 23.11 | 15.97 | 34.72 | 66.67 | 315 | 427 | 71.74 | 0.000 |
| clean | 17 | 57.53 | 10.69 | 57.06 | 53.49 | 20.97 | 10.23 | 17.19 | 14.16 | 6.86 | 28.56 | 64.71 | 205 | 282 | 81.67 | 0.000 |
| farfield | 12 | 63.32 | 26.43 | 62.97 | 59.54 | 34.67 | 25.87 | 31.81 | 24.00 | 17.82 | 33.14 | 58.33 | 363 | 463 | 68.06 | 0.000 |
| noise | 14 | 47.07 | 20.53 | 46.12 | 41.03 | 28.92 | 20.12 | 30.47 | 19.87 | 13.42 | 27.10 | 78.57 | 226 | 349 | 74.16 | 0.000 |
| overlap | 11 | 59.18 | 19.73 | 58.71 | 55.08 | 30.48 | 19.62 | 31.83 | 24.06 | 14.85 | 47.01 | 45.45 | 294 | 433 | 65.11 | 0.000 |
| phone | 22 | 61.34 | 34.41 | 60.95 | 55.89 | 40.35 | 33.82 | 48.66 | 33.53 | 26.58 | 43.75 | 68.18 | 477 | 607 | 64.80 | 0.000 |
| reverb | 8 | 32.19 | 16.19 | 31.23 | 27.14 | 23.81 | 14.94 | 20.30 | 17.59 | 10.89 | 21.76 | 87.50 | 235 | 351 | 80.14 | 0.000 |

### По файлам

| file | condition | dur s | spk ref/hyp | WER raw | WER norm | cpWER | DER | JER | RTF | notes |
|---|---|---|---|---|---|---|---|---|---|---|
| synth_0000_meeting_overlap | overlap | 278.5 | 2/2 | 39.93 | 18.26 | 22.11 | 13.83 | 20.36 | 0.0003 | sweep: asr.blank_penalty=0.0 |
| synth_0001_call_phone | phone | 217.5 | 2/2 | 52.45 | 35.64 | 36.03 | 33.47 | 40.99 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0002_meeting_farfield | farfield | 161.6 | 3/2 | 65.62 | 54.01 | 54.35 | 46.85 | 47.14 | 0.0001 | sweep: asr.blank_penalty=0.0 |
| synth_0003_call_phone | phone | 268.7 | 2/2 | 54.74 | 38.45 | 38.60 | 13.46 | 19.84 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0004_meeting_clean | clean | 150.5 | 2/2 | 26.17 | 7.60 | 11.08 | 6.29 | 6.47 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0006_meeting_clean | clean | 109.2 | 4/3 | 24.90 | 8.05 | 18.22 | 15.89 | 35.34 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0007_call_reverb | reverb | 71.4 | 3/3 | 48.67 | 34.54 | 40.91 | 35.07 | 25.45 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0010_meeting_noise | noise | 182.7 | 2/2 | 47.32 | 23.76 | 28.46 | 17.90 | 17.58 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0011_call_phone | phone | 109.0 | 3/3 | 28.91 | 9.76 | 17.56 | 21.86 | 20.32 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0012_meeting_noise | noise | 261.4 | 3/2 | 39.73 | 18.68 | 64.86 | 33.73 | 49.91 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0013_call_clean | clean | 143.3 | 2/2 | 27.80 | 9.38 | 13.89 | 17.09 | 16.89 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0014_meeting_farfield | farfield | 152.2 | 2/2 | 42.17 | 17.58 | 20.61 | 11.54 | 12.73 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0015_call_overlap | overlap | 208.8 | 4/2 | 47.74 | 25.76 | 54.07 | 30.35 | 63.70 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0016_meeting_farfield | farfield | 249.0 | 2/2 | 42.39 | 23.45 | 29.43 | 12.81 | 12.44 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0017_call_phone | phone | 75.9 | 4/1 | 63.08 | 53.57 | 124.21 | 75.59 | 92.46 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0018_meeting_noise | noise | 245.8 | 4/4 | 35.65 | 12.40 | 18.51 | 10.79 | 10.85 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0019_call_noise | noise | 91.3 | 2/2 | 32.81 | 13.73 | 13.73 | 17.80 | 16.45 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0023_call_phone | phone | 178.7 | 4/3 | 69.28 | 57.73 | 60.91 | 50.94 | 60.20 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0024_meeting_reverb | reverb | 249.3 | 2/2 | 33.84 | 12.98 | 13.37 | 8.40 | 8.91 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0025_call_noise | noise | 255.5 | 2/2 | 38.32 | 19.38 | 20.65 | 8.63 | 11.77 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0026_meeting_reverb | reverb | 73.1 | 4/4 | 38.89 | 22.13 | 22.13 | 23.16 | 10.92 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0027_call_clean | clean | 243.2 | 2/2 | 39.62 | 12.64 | 14.26 | 7.37 | 7.37 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0028_meeting_farfield | farfield | 229.3 | 2/2 | 63.03 | 52.74 | 56.80 | 36.89 | 33.91 | 0.0001 | sweep: asr.blank_penalty=0.0 |
| synth_0029_call_phone | phone | 82.3 | 2/2 | 47.46 | 35.84 | 36.99 | 20.36 | 18.92 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0031_call_phone | phone | 119.4 | 2/2 | 52.77 | 38.61 | 39.00 | 33.55 | 33.52 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0034_meeting_clean | clean | 191.1 | 4/4 | 31.88 | 8.64 | 13.35 | 11.37 | 12.40 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| synth_0035_call_overlap | overlap | 122.8 | 3/3 | 28.41 | 15.12 | 17.83 | 25.19 | 19.55 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| cats_c01-c02_v1_call_phone | phone | 87.8 | 3/1 | 32.57 | 21.10 | 110.09 | 58.83 | 84.10 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| cats_c01_v0_meeting_clean | clean | 58.6 | 3/3 | 21.10 | 11.01 | 11.01 | 10.22 | 12.25 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| cats_c02-c06_v1_meeting_farfield | farfield | 93.5 | 3/3 | 27.60 | 17.71 | 23.96 | 16.22 | 17.58 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| cats_c02_v0_call_phone | phone | 41.4 | 3/3 | 35.78 | 27.52 | 27.52 | 21.73 | 26.17 | 0.0003 | sweep: asr.blank_penalty=0.0 |
| cats_c06-c09_v1_meeting_farfield | farfield | 88.4 | 3/3 | 25.97 | 15.00 | 20.00 | 23.23 | 24.04 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| cats_c06_v0_call_noise | noise | 31.9 | 3/3 | 32.53 | 19.28 | 34.94 | 21.94 | 25.79 | 0.0003 | sweep: asr.blank_penalty=0.0 |
| cats_c07-c08_v1_call_phone | phone | 45.7 | 2/2 | 27.43 | 19.47 | 28.32 | 26.88 | 28.47 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| cats_c07_v0_meeting_reverb | reverb | 22.7 | 2/6 | 43.59 | 28.21 | 97.44 | 73.79 | 62.60 | 0.0004 | sweep: asr.blank_penalty=0.0 |
| cats_c08-c10_v1_meeting_reverb | reverb | 66.2 | 2/2 | 22.90 | 14.62 | 18.46 | 21.13 | 22.05 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| cats_c08_v0_call_reverb | reverb | 29.1 | 2/2 | 24.32 | 16.22 | 22.97 | 18.09 | 17.95 | 0.0003 | sweep: asr.blank_penalty=0.0 |
| cats_c09-c11_v1_call_phone | phone | 61.7 | 3/3 | 34.38 | 20.75 | 47.34 | 31.94 | 36.69 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| cats_c09_v0_meeting_overlap | overlap | 30.1 | 3/4 | 42.86 | 36.08 | 65.98 | 55.58 | 56.64 | 0.0003 | sweep: asr.blank_penalty=0.0 |
| cats_c10-c12_v1_meeting_noise | noise | 55.9 | 2/2 | 42.02 | 28.81 | 34.75 | 31.44 | 31.06 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| cats_c10_v0_call_clean | clean | 25.6 | 2/1 | 29.83 | 12.50 | 103.57 | 60.14 | 76.00 | 0.0004 | sweep: asr.blank_penalty=0.0 |
| cats_c11-c13_v1_call_clean | clean | 57.8 | 3/3 | 28.99 | 16.36 | 29.70 | 18.81 | 24.05 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| cats_c11_v0_meeting_noise | noise | 44.0 | 3/3 | 24.47 | 10.99 | 20.88 | 14.32 | 15.18 | 0.0003 | sweep: asr.blank_penalty=0.0 |
| cats_c12_v0_call_phone | phone | 18.9 | 2/1 | 33.87 | 20.97 | 70.97 | 54.52 | 71.32 | 0.0005 | sweep: asr.blank_penalty=0.0 |
| cats_c13-c16_v1_call_clean | clean | 45.1 | 3/3 | 37.61 | 22.41 | 27.59 | 25.51 | 27.73 | 0.0003 | sweep: asr.blank_penalty=0.0 |
| cats_c13_v0_meeting_reverb | reverb | 40.8 | 3/3 | 17.33 | 5.41 | 5.41 | 10.89 | 11.81 | 0.0003 | sweep: asr.blank_penalty=0.0 |
| cats_c14-c23_v1_meeting_farfield | farfield | 84.4 | 4/3 | 33.53 | 23.95 | 46.71 | 28.43 | 42.61 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| cats_c14_v0_call_overlap | overlap | 26.5 | 4/2 | 71.08 | 55.70 | 75.95 | 67.39 | 76.16 | 0.0003 | sweep: asr.blank_penalty=0.0 |
| cats_c16-c18_v1_meeting_clean | clean | 59.8 | 3/2 | 20.00 | 6.36 | 55.45 | 53.81 | 58.42 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| cats_c16_v0_call_clean | clean | 20.4 | 3/2 | 57.14 | 23.81 | 66.67 | 47.01 | 55.56 | 0.0005 | sweep: asr.blank_penalty=0.0 |
| cats_c17-c20_v1_call_phone | phone | 44.3 | 2/2 | 27.78 | 15.87 | 27.78 | 23.77 | 27.92 | 0.0003 | sweep: asr.blank_penalty=0.0 |
| cats_c17_v0_meeting_reverb | reverb | 28.7 | 2/2 | 11.32 | 5.66 | 5.66 | 14.52 | 14.39 | 0.0003 | sweep: asr.blank_penalty=0.0 |
| cats_c18-c19_v1_meeting_farfield | farfield | 66.6 | 3/2 | 47.79 | 36.50 | 56.20 | 47.19 | 53.45 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| cats_c18_v0_call_phone | phone | 24.9 | 3/2 | 23.53 | 11.77 | 58.82 | 46.94 | 56.20 | 0.0004 | sweep: asr.blank_penalty=0.0 |
| cats_c19-c21_v1_call_phone | phone | 59.9 | 3/3 | 82.47 | 73.55 | 77.42 | 61.52 | 62.17 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| cats_c19_v0_meeting_overlap | overlap | 26.1 | 3/2 | 47.06 | 36.23 | 75.36 | 49.37 | 66.86 | 0.0004 | sweep: asr.blank_penalty=0.0 |
| cats_c20-c22_v1_meeting_noise | noise | 69.2 | 2/2 | 20.61 | 7.63 | 10.69 | 19.79 | 19.75 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| cats_c20_v0_call_phone | phone | 26.9 | 2/1 | 20.55 | 8.22 | 82.19 | 65.66 | 77.26 | 0.0004 | sweep: asr.blank_penalty=0.0 |
| cats_c21_v0_meeting_phone | phone | 40.3 | 3/3 | 27.91 | 18.61 | 22.09 | 16.76 | 18.10 | 0.0003 | sweep: asr.blank_penalty=0.0 |
| cats_c22_v0_call_clean | clean | 16.1 | 2/2 | 37.93 | 25.86 | 37.93 | 35.09 | 38.56 | 0.0007 | sweep: asr.blank_penalty=0.0 |
| cats_c23_v0_meeting_clean | clean | 42.9 | 4/3 | 24.14 | 18.18 | 44.32 | 25.78 | 48.21 | 0.0003 | sweep: asr.blank_penalty=0.0 |
| cats_c25_v0_meeting_noise | noise | 48.5 | 2/2 | 30.95 | 18.07 | 18.07 | 9.94 | 9.87 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| cats_c26_v0_call_phone | phone | 25.5 | 3/3 | 25.00 | 9.38 | 12.50 | 14.08 | 14.43 | 0.0004 | sweep: asr.blank_penalty=0.0 |
| cats_c28_v0_call_noise | noise | 23.7 | 3/2 | 33.33 | 17.74 | 56.45 | 32.93 | 58.61 | 0.0004 | sweep: asr.blank_penalty=0.0 |
| cats_c29_v0_meeting_farfield | farfield | 26.0 | 2/1 | 33.90 | 20.34 | 66.10 | 63.43 | 73.60 | 0.0004 | sweep: asr.blank_penalty=0.0 |
| cats_c32_v0_call_noise | noise | 19.3 | 2/2 | 47.06 | 41.18 | 41.18 | 36.43 | 48.26 | 0.0006 | sweep: asr.blank_penalty=0.0 |
| cats_c34_v0_call_overlap | overlap | 30.5 | 2/2 | 35.13 | 12.16 | 24.32 | 18.30 | 22.67 | 0.0004 | sweep: asr.blank_penalty=0.0 |
| cats_c36_v0_call_phone | phone | 26.1 | 2/2 | 49.18 | 38.33 | 50.00 | 49.60 | 53.12 | 0.0003 | sweep: asr.blank_penalty=0.0 |
| cats_c37_v0_meeting_overlap | overlap | 25.2 | 3/1 | 21.88 | 17.19 | 31.25 | 33.27 | 74.78 | 0.0004 | sweep: asr.blank_penalty=0.0 |
| cats_c39_v0_meeting_overlap | overlap | 26.0 | 4/3 | 58.46 | 38.46 | 47.69 | 51.11 | 58.57 | 0.0004 | sweep: asr.blank_penalty=0.0 |
| cats_c40_v0_call_overlap | overlap | 21.5 | 3/3 | 58.46 | 43.94 | 51.52 | 44.39 | 45.74 | 0.0004 | sweep: asr.blank_penalty=0.0 |
| ensynth_0001_call_clean | clean | 166.8 | 2/2 | 99.15 | 7.07 | 7.73 | 7.94 | 7.80 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| ensynth_0003_call_overlap | overlap | 216.8 | 2/2 | 99.38 | 8.40 | 12.98 | 10.82 | 12.09 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| ensynth_0004_meeting_clean | clean | 158.6 | 3/3 | 97.63 | 2.32 | 2.32 | 6.62 | 6.60 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| ensynth_0005_call_noise | noise | 125.5 | 2/2 | 97.30 | 18.55 | 22.41 | 14.45 | 15.81 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| ensynth_0008_meeting_farfield | farfield | 103.1 | 2/2 | 98.16 | 13.38 | 13.38 | 9.46 | 10.94 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| ensynth_0009_call_phone | phone | 138.9 | 2/2 | 99.55 | 5.51 | 9.03 | 10.52 | 12.20 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| ensynth_0011_call_clean | clean | 134.4 | 3/3 | 99.09 | 6.09 | 8.13 | 7.88 | 9.25 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| ensynth_0012_meeting_farfield | farfield | 232.5 | 2/2 | 97.64 | 12.50 | 12.50 | 10.35 | 10.28 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| ensynth_0013_call_phone | phone | 176.0 | 3/2 | 97.19 | 26.01 | 45.13 | 20.17 | 46.86 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| mix_0000_meeting_noise | noise | 103.8 | 3/2 | 83.47 | 54.96 | 54.96 | 42.60 | 48.50 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| mix_0002_meeting_clean | clean | 125.6 | 4/3 | 83.33 | 30.45 | 35.99 | 21.99 | 42.68 | 0.0002 | sweep: asr.blank_penalty=0.0 |
| mix_0003_call_phone | phone | 122.1 | 3/3 | 93.44 | 74.93 | 75.49 | 63.40 | 61.32 | 0.0001 | sweep: asr.blank_penalty=0.0 |
| mix_0004_meeting_farfield | farfield | 74.5 | 4/2 | 100.00 | 36.65 | 44.10 | 34.25 | 58.90 | 0.0002 | sweep: asr.blank_penalty=0.0 |

## refpipe-bp-robust-dev-bp1.0

tier=medium, platform=desktop, device=Darwin arm64 (arm), threads=2, dataset=refpipe-baseline-dev, config_hash=7c3795c7edb4afefa5acd37db59cae5db877dcf80154c5c554247c4602bc8a89

### По условиям

| group | files | WER raw | WER norm | WER punct | WER p-sep | WER p-lc | WER num | cpWER | DER | DER ±.25 | JER | spk acc | ts start MAE ms | ts end MAE ms | start<300ms | RTF |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ALL | 84 | 55.85 | 21.87 | 55.29 | 50.96 | 30.49 | 21.35 | 31.30 | 21.73 | 14.64 | 33.55 | 64.29 | 291 | 424 | 74.39 | 0.000 |
| clean | 17 | 57.21 | 10.55 | 56.74 | 53.42 | 20.95 | 10.07 | 17.14 | 13.56 | 6.55 | 27.87 | 64.71 | 184 | 287 | 83.47 | 0.000 |
| farfield | 12 | 63.15 | 25.78 | 62.79 | 59.44 | 34.47 | 25.18 | 31.93 | 23.11 | 16.91 | 33.34 | 58.33 | 353 | 425 | 70.79 | 0.000 |
| noise | 14 | 46.36 | 19.79 | 45.46 | 40.45 | 28.36 | 19.32 | 31.05 | 19.77 | 13.39 | 27.14 | 71.43 | 213 | 349 | 76.36 | 0.000 |
| overlap | 11 | 58.32 | 18.64 | 57.71 | 53.96 | 29.54 | 18.53 | 26.41 | 20.83 | 11.63 | 42.03 | 54.55 | 269 | 447 | 70.37 | 0.000 |
| phone | 22 | 60.51 | 33.31 | 60.10 | 54.93 | 39.21 | 32.68 | 48.00 | 31.15 | 24.05 | 42.62 | 59.09 | 430 | 605 | 67.61 | 0.000 |
| reverb | 8 | 32.11 | 15.65 | 31.14 | 27.00 | 23.74 | 14.40 | 19.41 | 16.27 | 9.37 | 20.56 | 87.50 | 221 | 349 | 81.22 | 0.000 |

### По файлам

| file | condition | dur s | spk ref/hyp | WER raw | WER norm | cpWER | DER | JER | RTF | notes |
|---|---|---|---|---|---|---|---|---|---|---|
| synth_0000_meeting_overlap | overlap | 278.5 | 2/2 | 39.61 | 17.92 | 23.45 | 15.13 | 21.56 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| synth_0001_call_phone | phone | 217.5 | 2/2 | 51.70 | 33.91 | 34.49 | 29.87 | 36.59 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0002_meeting_farfield | farfield | 161.6 | 3/2 | 65.28 | 53.66 | 52.96 | 46.72 | 50.81 | 0.0001 | sweep: asr.blank_penalty=1.0 |
| synth_0003_call_phone | phone | 268.7 | 2/2 | 55.17 | 37.72 | 37.87 | 11.84 | 18.44 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0004_meeting_clean | clean | 150.5 | 2/2 | 26.48 | 7.91 | 11.08 | 5.88 | 5.97 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0006_meeting_clean | clean | 109.2 | 4/3 | 23.24 | 5.93 | 19.07 | 16.41 | 36.54 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0007_call_reverb | reverb | 71.4 | 3/3 | 50.44 | 33.64 | 37.27 | 31.63 | 22.86 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0010_meeting_noise | noise | 182.7 | 2/2 | 47.06 | 24.02 | 27.94 | 12.65 | 12.04 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0011_call_phone | phone | 109.0 | 3/3 | 26.54 | 8.78 | 15.61 | 21.29 | 19.04 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0012_meeting_noise | noise | 261.4 | 3/2 | 38.18 | 17.27 | 66.67 | 34.43 | 50.24 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0013_call_clean | clean | 143.3 | 2/2 | 27.46 | 9.38 | 13.89 | 17.09 | 16.89 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0014_meeting_farfield | farfield | 152.2 | 2/2 | 43.98 | 17.27 | 21.82 | 9.52 | 11.20 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0015_call_overlap | overlap | 208.8 | 4/4 | 46.73 | 24.92 | 29.15 | 17.20 | 20.10 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0016_meeting_farfield | farfield | 249.0 | 2/2 | 41.52 | 23.01 | 28.54 | 11.95 | 10.89 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0017_call_phone | phone | 75.9 | 4/1 | 61.92 | 52.78 | 128.97 | 76.04 | 92.48 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0018_meeting_noise | noise | 245.8 | 4/4 | 34.90 | 11.64 | 20.04 | 13.67 | 14.36 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0019_call_noise | noise | 91.3 | 2/2 | 30.47 | 11.37 | 16.47 | 17.04 | 17.34 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0023_call_phone | phone | 178.7 | 4/3 | 68.83 | 57.50 | 58.18 | 45.52 | 55.21 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0024_meeting_reverb | reverb | 249.3 | 2/2 | 34.59 | 12.79 | 13.57 | 7.54 | 7.77 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0025_call_noise | noise | 255.5 | 2/2 | 38.04 | 19.10 | 20.65 | 8.64 | 11.75 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0026_meeting_reverb | reverb | 73.1 | 4/4 | 33.33 | 18.85 | 22.13 | 20.77 | 10.11 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0027_call_clean | clean | 243.2 | 2/2 | 38.82 | 12.48 | 13.78 | 6.62 | 6.94 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0028_meeting_farfield | farfield | 229.3 | 2/2 | 62.09 | 51.31 | 56.33 | 33.21 | 31.56 | 0.0001 | sweep: asr.blank_penalty=1.0 |
| synth_0029_call_phone | phone | 82.3 | 2/2 | 45.20 | 35.26 | 35.26 | 15.93 | 14.39 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0031_call_phone | phone | 119.4 | 2/2 | 47.97 | 35.13 | 34.75 | 32.42 | 30.48 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0034_meeting_clean | clean | 191.1 | 4/4 | 31.36 | 8.90 | 14.92 | 12.13 | 12.82 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| synth_0035_call_overlap | overlap | 122.8 | 3/3 | 26.89 | 14.73 | 16.67 | 24.40 | 18.26 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| cats_c01-c02_v1_call_phone | phone | 87.8 | 3/2 | 33.49 | 19.73 | 86.24 | 45.41 | 57.36 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| cats_c01_v0_meeting_clean | clean | 58.6 | 3/2 | 22.94 | 13.76 | 43.12 | 30.34 | 50.90 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| cats_c02-c06_v1_meeting_farfield | farfield | 93.5 | 3/3 | 26.04 | 18.23 | 27.08 | 16.34 | 18.51 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| cats_c02_v0_call_phone | phone | 41.4 | 3/3 | 38.53 | 26.61 | 35.78 | 20.33 | 22.39 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c06-c09_v1_meeting_farfield | farfield | 88.4 | 3/3 | 27.62 | 12.78 | 20.56 | 22.54 | 23.73 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| cats_c06_v0_call_noise | noise | 31.9 | 3/3 | 32.53 | 19.28 | 36.14 | 23.97 | 26.97 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c07-c08_v1_call_phone | phone | 45.7 | 2/2 | 24.78 | 14.16 | 24.78 | 26.74 | 28.96 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c07_v0_meeting_reverb | reverb | 22.7 | 2/3 | 48.72 | 33.33 | 64.10 | 56.64 | 51.13 | 0.0004 | sweep: asr.blank_penalty=1.0 |
| cats_c08-c10_v1_meeting_reverb | reverb | 66.2 | 2/2 | 21.37 | 13.08 | 16.92 | 19.63 | 20.41 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| cats_c08_v0_call_reverb | reverb | 29.1 | 2/2 | 24.32 | 16.22 | 29.73 | 21.60 | 22.59 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c09-c11_v1_call_phone | phone | 61.7 | 3/3 | 34.90 | 19.15 | 45.21 | 27.99 | 33.97 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| cats_c09_v0_meeting_overlap | overlap | 30.1 | 3/3 | 41.84 | 32.99 | 51.55 | 49.99 | 51.31 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c10-c12_v1_meeting_noise | noise | 55.9 | 2/2 | 39.50 | 27.97 | 35.59 | 31.35 | 31.23 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| cats_c10_v0_call_clean | clean | 25.6 | 2/1 | 31.58 | 10.71 | 105.36 | 60.14 | 76.00 | 0.0004 | sweep: asr.blank_penalty=1.0 |
| cats_c11-c13_v1_call_clean | clean | 57.8 | 3/3 | 29.59 | 16.97 | 33.33 | 19.20 | 23.31 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c11_v0_meeting_noise | noise | 44.0 | 3/3 | 23.40 | 9.89 | 23.08 | 16.48 | 17.15 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c12_v0_call_phone | phone | 18.9 | 2/1 | 29.03 | 19.36 | 72.58 | 54.52 | 71.32 | 0.0004 | sweep: asr.blank_penalty=1.0 |
| cats_c13-c16_v1_call_clean | clean | 45.1 | 3/3 | 33.33 | 19.83 | 26.72 | 21.72 | 23.15 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c13_v0_meeting_reverb | reverb | 40.8 | 3/3 | 17.33 | 5.41 | 9.46 | 14.74 | 15.42 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c14-c23_v1_meeting_farfield | farfield | 84.4 | 4/3 | 34.12 | 23.35 | 44.31 | 26.11 | 41.37 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| cats_c14_v0_call_overlap | overlap | 26.5 | 4/3 | 62.65 | 43.04 | 68.35 | 52.12 | 62.97 | 0.0004 | sweep: asr.blank_penalty=1.0 |
| cats_c16-c18_v1_meeting_clean | clean | 59.8 | 3/3 | 18.18 | 3.64 | 18.18 | 21.60 | 23.32 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| cats_c16_v0_call_clean | clean | 20.4 | 3/2 | 57.14 | 28.57 | 69.05 | 46.77 | 55.48 | 0.0004 | sweep: asr.blank_penalty=1.0 |
| cats_c17-c20_v1_call_phone | phone | 44.3 | 2/2 | 26.98 | 19.05 | 32.54 | 24.56 | 28.75 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c17_v0_meeting_reverb | reverb | 28.7 | 2/2 | 11.32 | 5.66 | 5.66 | 14.32 | 14.19 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c18-c19_v1_meeting_farfield | farfield | 66.6 | 3/2 | 44.85 | 30.66 | 60.58 | 51.68 | 58.26 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| cats_c18_v0_call_phone | phone | 24.9 | 3/2 | 23.53 | 11.77 | 60.29 | 46.12 | 56.01 | 0.0004 | sweep: asr.blank_penalty=1.0 |
| cats_c19-c21_v1_call_phone | phone | 59.9 | 3/2 | 81.17 | 69.03 | 82.58 | 62.33 | 66.23 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| cats_c19_v0_meeting_overlap | overlap | 26.1 | 3/2 | 44.12 | 30.44 | 81.16 | 46.06 | 65.23 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c20-c22_v1_meeting_noise | noise | 69.2 | 2/2 | 18.32 | 6.11 | 10.69 | 19.25 | 19.44 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| cats_c20_v0_call_phone | phone | 26.9 | 2/1 | 21.92 | 8.22 | 82.19 | 65.66 | 77.26 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c21_v0_meeting_phone | phone | 40.3 | 3/3 | 25.58 | 18.61 | 25.58 | 19.05 | 20.14 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c22_v0_call_clean | clean | 16.1 | 2/2 | 37.93 | 27.59 | 34.48 | 30.70 | 33.12 | 0.0005 | sweep: asr.blank_penalty=1.0 |
| cats_c23_v0_meeting_clean | clean | 42.9 | 4/3 | 26.44 | 18.18 | 44.32 | 24.57 | 45.45 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c25_v0_meeting_noise | noise | 48.5 | 2/2 | 29.76 | 16.87 | 19.28 | 11.13 | 11.76 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| cats_c26_v0_call_phone | phone | 25.5 | 3/2 | 21.88 | 6.25 | 68.75 | 40.03 | 55.23 | 0.0004 | sweep: asr.blank_penalty=1.0 |
| cats_c28_v0_call_noise | noise | 23.7 | 3/2 | 33.33 | 17.74 | 45.16 | 31.06 | 57.30 | 0.0004 | sweep: asr.blank_penalty=1.0 |
| cats_c29_v0_meeting_farfield | farfield | 26.0 | 2/1 | 33.90 | 20.34 | 67.80 | 63.43 | 73.60 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c32_v0_call_noise | noise | 19.3 | 2/3 | 47.06 | 37.26 | 47.06 | 36.31 | 47.16 | 0.0005 | sweep: asr.blank_penalty=1.0 |
| cats_c34_v0_call_overlap | overlap | 30.5 | 2/2 | 32.43 | 12.16 | 24.32 | 18.44 | 22.80 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c36_v0_call_phone | phone | 26.1 | 2/2 | 36.07 | 26.67 | 45.00 | 30.13 | 38.74 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c37_v0_meeting_overlap | overlap | 25.2 | 3/1 | 25.00 | 17.19 | 34.38 | 33.45 | 74.82 | 0.0004 | sweep: asr.blank_penalty=1.0 |
| cats_c39_v0_meeting_overlap | overlap | 26.0 | 4/3 | 56.92 | 30.77 | 52.31 | 47.20 | 57.94 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| cats_c40_v0_call_overlap | overlap | 21.5 | 3/2 | 56.92 | 43.94 | 56.06 | 49.71 | 55.23 | 0.0004 | sweep: asr.blank_penalty=1.0 |
| ensynth_0001_call_clean | clean | 166.8 | 2/2 | 99.15 | 7.07 | 7.73 | 7.94 | 7.80 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| ensynth_0003_call_overlap | overlap | 216.8 | 2/2 | 99.38 | 8.40 | 12.98 | 10.82 | 12.09 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| ensynth_0004_meeting_clean | clean | 158.6 | 3/3 | 97.63 | 2.32 | 2.32 | 6.62 | 6.60 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| ensynth_0005_call_noise | noise | 125.5 | 2/2 | 97.30 | 18.55 | 22.41 | 14.45 | 15.81 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| ensynth_0008_meeting_farfield | farfield | 103.1 | 2/2 | 98.16 | 13.38 | 13.38 | 9.46 | 10.94 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| ensynth_0009_call_phone | phone | 138.9 | 2/2 | 99.55 | 5.51 | 9.03 | 10.52 | 12.20 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| ensynth_0011_call_clean | clean | 134.4 | 3/3 | 99.09 | 6.09 | 8.13 | 7.88 | 9.25 | 0.0003 | sweep: asr.blank_penalty=1.0 |
| ensynth_0012_meeting_farfield | farfield | 232.5 | 2/2 | 97.64 | 12.50 | 12.50 | 10.35 | 10.28 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| ensynth_0013_call_phone | phone | 176.0 | 3/2 | 97.19 | 26.01 | 45.13 | 20.17 | 46.86 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| mix_0000_meeting_noise | noise | 103.8 | 3/2 | 84.71 | 54.13 | 54.13 | 41.15 | 47.49 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| mix_0002_meeting_clean | clean | 125.6 | 4/3 | 82.64 | 29.76 | 33.91 | 19.90 | 40.33 | 0.0002 | sweep: asr.blank_penalty=1.0 |
| mix_0003_call_phone | phone | 122.1 | 3/3 | 92.90 | 74.09 | 74.09 | 57.52 | 55.59 | 0.0001 | sweep: asr.blank_penalty=1.0 |
| mix_0004_meeting_farfield | farfield | 74.5 | 4/2 | 100.00 | 36.65 | 44.10 | 34.25 | 58.90 | 0.0002 | sweep: asr.blank_penalty=1.0 |

## refpipe-bp-robust-dev-bp1.0-k1thr030

tier=medium, platform=desktop, device=Darwin arm64 (arm), threads=2, dataset=refpipe-baseline-dev, config_hash=83001925386508712225eecf7f12f206f082ea973b9e24f1c8ae8eed732031ff

### По условиям

| group | files | WER raw | WER norm | WER punct | WER p-sep | WER p-lc | WER num | cpWER | DER | DER ±.25 | JER | spk acc | ts start MAE ms | ts end MAE ms | start<300ms | RTF |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ALL | 84 | 55.85 | 21.87 | 55.29 | 50.96 | 30.49 | 21.35 | 31.03 | 21.58 | 14.54 | 32.71 | 66.67 | 291 | 424 | 74.39 | 0.020 |
| clean | 17 | 57.21 | 10.55 | 56.74 | 53.42 | 20.95 | 10.07 | 17.14 | 13.56 | 6.55 | 27.87 | 64.71 | 184 | 287 | 83.47 | 0.022 |
| farfield | 12 | 63.15 | 25.78 | 62.79 | 59.44 | 34.47 | 25.18 | 31.93 | 23.11 | 16.91 | 33.34 | 58.33 | 353 | 425 | 70.79 | 0.016 |
| noise | 14 | 46.36 | 19.79 | 45.46 | 40.45 | 28.36 | 19.32 | 31.05 | 19.77 | 13.39 | 27.14 | 71.43 | 213 | 349 | 76.36 | 0.024 |
| overlap | 11 | 58.32 | 18.64 | 57.71 | 53.96 | 29.54 | 18.53 | 26.41 | 20.83 | 11.63 | 42.03 | 54.55 | 269 | 447 | 70.37 | 0.023 |
| phone | 22 | 60.51 | 33.31 | 60.10 | 54.93 | 39.21 | 32.68 | 46.93 | 30.52 | 23.63 | 39.40 | 68.18 | 430 | 605 | 67.61 | 0.019 |
| reverb | 8 | 32.11 | 15.65 | 31.14 | 27.00 | 23.74 | 14.40 | 19.41 | 16.27 | 9.37 | 20.56 | 87.50 | 221 | 349 | 81.22 | 0.010 |

### По файлам

| file | condition | dur s | spk ref/hyp | WER raw | WER norm | cpWER | DER | JER | RTF | notes |
|---|---|---|---|---|---|---|---|---|---|---|
| synth_0000_meeting_overlap | overlap | 278.5 | 2/2 | 39.61 | 17.92 | 23.45 | 15.13 | 21.56 | 0.0235 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0001_call_phone | phone | 217.5 | 2/2 | 51.70 | 33.91 | 34.49 | 29.87 | 36.59 | 0.0201 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0002_meeting_farfield | farfield | 161.6 | 3/2 | 65.28 | 53.66 | 52.96 | 46.72 | 50.81 | 0.0192 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0003_call_phone | phone | 268.7 | 2/2 | 55.17 | 37.72 | 37.87 | 11.84 | 18.44 | 0.02 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0004_meeting_clean | clean | 150.5 | 2/2 | 26.48 | 7.91 | 11.08 | 5.88 | 5.97 | 0.0232 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0006_meeting_clean | clean | 109.2 | 4/3 | 23.24 | 5.93 | 19.07 | 16.41 | 36.54 | 0.0199 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0007_call_reverb | reverb | 71.4 | 3/3 | 50.44 | 33.64 | 37.27 | 31.63 | 22.86 | 0.0143 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0010_meeting_noise | noise | 182.7 | 2/2 | 47.06 | 24.02 | 27.94 | 12.65 | 12.04 | 0.0239 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0011_call_phone | phone | 109.0 | 3/3 | 26.54 | 8.78 | 15.61 | 21.29 | 19.04 | 0.0137 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0012_meeting_noise | noise | 261.4 | 3/2 | 38.18 | 17.27 | 66.67 | 34.43 | 50.24 | 0.025 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0013_call_clean | clean | 143.3 | 2/2 | 27.46 | 9.38 | 13.89 | 17.09 | 16.89 | 0.0232 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0014_meeting_farfield | farfield | 152.2 | 2/2 | 43.98 | 17.27 | 21.82 | 9.52 | 11.20 | 0.0086 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0015_call_overlap | overlap | 208.8 | 4/4 | 46.73 | 24.92 | 29.15 | 17.20 | 20.10 | 0.0263 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0016_meeting_farfield | farfield | 249.0 | 2/2 | 41.52 | 23.01 | 28.54 | 11.95 | 10.89 | 0.0199 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0017_call_phone | phone | 75.9 | 4/1 | 61.92 | 52.78 | 128.97 | 76.04 | 92.48 | 0.0154 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0018_meeting_noise | noise | 245.8 | 4/4 | 34.90 | 11.64 | 20.04 | 13.67 | 14.36 | 0.0246 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0019_call_noise | noise | 91.3 | 2/2 | 30.47 | 11.37 | 16.47 | 17.04 | 17.34 | 0.0313 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0023_call_phone | phone | 178.7 | 4/3 | 68.83 | 57.50 | 58.18 | 45.52 | 55.21 | 0.0202 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0024_meeting_reverb | reverb | 249.3 | 2/2 | 34.59 | 12.79 | 13.57 | 7.54 | 7.77 | 0.0062 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0025_call_noise | noise | 255.5 | 2/2 | 38.04 | 19.10 | 20.65 | 8.64 | 11.75 | 0.0249 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0026_meeting_reverb | reverb | 73.1 | 4/4 | 33.33 | 18.85 | 22.13 | 20.77 | 10.11 | 0.0188 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0027_call_clean | clean | 243.2 | 2/2 | 38.82 | 12.48 | 13.78 | 6.62 | 6.94 | 0.0251 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0028_meeting_farfield | farfield | 229.3 | 2/2 | 62.09 | 51.31 | 56.33 | 33.21 | 31.56 | 0.0125 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0029_call_phone | phone | 82.3 | 2/2 | 45.20 | 35.26 | 35.26 | 15.93 | 14.39 | 0.0127 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0031_call_phone | phone | 119.4 | 2/2 | 47.97 | 35.13 | 34.75 | 32.42 | 30.48 | 0.011 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0034_meeting_clean | clean | 191.1 | 4/4 | 31.36 | 8.90 | 14.92 | 12.13 | 12.82 | 0.0203 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0035_call_overlap | overlap | 122.8 | 3/3 | 26.89 | 14.73 | 16.67 | 24.40 | 18.26 | 0.0192 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c01-c02_v1_call_phone | phone | 87.8 | 3/2 | 33.49 | 19.73 | 86.24 | 45.41 | 57.36 | 0.02 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c01_v0_meeting_clean | clean | 58.6 | 3/2 | 22.94 | 13.76 | 43.12 | 30.34 | 50.90 | 0.0242 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c02-c06_v1_meeting_farfield | farfield | 93.5 | 3/3 | 26.04 | 18.23 | 27.08 | 16.34 | 18.51 | 0.0123 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c02_v0_call_phone | phone | 41.4 | 3/3 | 38.53 | 26.61 | 35.78 | 20.33 | 22.39 | 0.0305 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c06-c09_v1_meeting_farfield | farfield | 88.4 | 3/3 | 27.62 | 12.78 | 20.56 | 22.54 | 23.73 | 0.0199 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c06_v0_call_noise | noise | 31.9 | 3/3 | 32.53 | 19.28 | 36.14 | 23.97 | 26.97 | 0.0259 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c07-c08_v1_call_phone | phone | 45.7 | 2/2 | 24.78 | 14.16 | 24.78 | 26.74 | 28.96 | 0.0229 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c07_v0_meeting_reverb | reverb | 22.7 | 2/3 | 48.72 | 33.33 | 64.10 | 56.64 | 51.13 | 0.0174 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c08-c10_v1_meeting_reverb | reverb | 66.2 | 2/2 | 21.37 | 13.08 | 16.92 | 19.63 | 20.41 | 0.0033 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c08_v0_call_reverb | reverb | 29.1 | 2/2 | 24.32 | 16.22 | 29.73 | 21.60 | 22.59 | 0.0142 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c09-c11_v1_call_phone | phone | 61.7 | 3/3 | 34.90 | 19.15 | 45.21 | 27.99 | 33.97 | 0.0283 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c09_v0_meeting_overlap | overlap | 30.1 | 3/3 | 41.84 | 32.99 | 51.55 | 49.99 | 51.31 | 0.0204 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c10-c12_v1_meeting_noise | noise | 55.9 | 2/2 | 39.50 | 27.97 | 35.59 | 31.35 | 31.23 | 0.0212 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c10_v0_call_clean | clean | 25.6 | 2/1 | 31.58 | 10.71 | 105.36 | 60.14 | 76.00 | 0.0223 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c11-c13_v1_call_clean | clean | 57.8 | 3/3 | 29.59 | 16.97 | 33.33 | 19.20 | 23.31 | 0.0244 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c11_v0_meeting_noise | noise | 44.0 | 3/3 | 23.40 | 9.89 | 23.08 | 16.48 | 17.15 | 0.0218 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c12_v0_call_phone | phone | 18.9 | 2/2 | 29.03 | 19.36 | 46.77 | 37.49 | 44.95 | 0.0204 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c13-c16_v1_call_clean | clean | 45.1 | 3/3 | 33.33 | 19.83 | 26.72 | 21.72 | 23.15 | 0.0214 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c13_v0_meeting_reverb | reverb | 40.8 | 3/3 | 17.33 | 5.41 | 9.46 | 14.74 | 15.42 | 0.0093 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c14-c23_v1_meeting_farfield | farfield | 84.4 | 4/3 | 34.12 | 23.35 | 44.31 | 26.11 | 41.37 | 0.0073 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c14_v0_call_overlap | overlap | 26.5 | 4/3 | 62.65 | 43.04 | 68.35 | 52.12 | 62.97 | 0.0201 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c16-c18_v1_meeting_clean | clean | 59.8 | 3/3 | 18.18 | 3.64 | 18.18 | 21.60 | 23.32 | 0.0202 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c16_v0_call_clean | clean | 20.4 | 3/2 | 57.14 | 28.57 | 69.05 | 46.77 | 55.48 | 0.0219 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c17-c20_v1_call_phone | phone | 44.3 | 2/2 | 26.98 | 19.05 | 32.54 | 24.56 | 28.75 | 0.0186 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c17_v0_meeting_reverb | reverb | 28.7 | 2/2 | 11.32 | 5.66 | 5.66 | 14.32 | 14.19 | 0.0212 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c18-c19_v1_meeting_farfield | farfield | 66.6 | 3/2 | 44.85 | 30.66 | 60.58 | 51.68 | 58.26 | 0.0088 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c18_v0_call_phone | phone | 24.9 | 3/2 | 23.53 | 11.77 | 60.29 | 46.12 | 56.01 | 0.0238 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c19-c21_v1_call_phone | phone | 59.9 | 3/2 | 81.17 | 69.03 | 82.58 | 62.33 | 66.23 | 0.0139 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c19_v0_meeting_overlap | overlap | 26.1 | 3/2 | 44.12 | 30.44 | 81.16 | 46.06 | 65.23 | 0.0172 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c20-c22_v1_meeting_noise | noise | 69.2 | 2/2 | 18.32 | 6.11 | 10.69 | 19.25 | 19.44 | 0.0193 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c20_v0_call_phone | phone | 26.9 | 2/2 | 21.92 | 8.22 | 27.40 | 25.90 | 32.87 | 0.0215 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c21_v0_meeting_phone | phone | 40.3 | 3/3 | 25.58 | 18.61 | 25.58 | 19.05 | 20.14 | 0.0194 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c22_v0_call_clean | clean | 16.1 | 2/2 | 37.93 | 27.59 | 34.48 | 30.70 | 33.12 | 0.0197 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c23_v0_meeting_clean | clean | 42.9 | 4/3 | 26.44 | 18.18 | 44.32 | 24.57 | 45.45 | 0.0216 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c25_v0_meeting_noise | noise | 48.5 | 2/2 | 29.76 | 16.87 | 19.28 | 11.13 | 11.76 | 0.0202 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c26_v0_call_phone | phone | 25.5 | 3/2 | 21.88 | 6.25 | 68.75 | 40.03 | 55.23 | 0.02 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c28_v0_call_noise | noise | 23.7 | 3/2 | 33.33 | 17.74 | 45.16 | 31.06 | 57.30 | 0.0214 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c29_v0_meeting_farfield | farfield | 26.0 | 2/1 | 33.90 | 20.34 | 67.80 | 63.43 | 73.60 | 0.015 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c32_v0_call_noise | noise | 19.3 | 2/3 | 47.06 | 37.26 | 47.06 | 36.31 | 47.16 | 0.0197 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c34_v0_call_overlap | overlap | 30.5 | 2/2 | 32.43 | 12.16 | 24.32 | 18.44 | 22.80 | 0.0185 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c36_v0_call_phone | phone | 26.1 | 2/2 | 36.07 | 26.67 | 45.00 | 30.13 | 38.74 | 0.0222 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c37_v0_meeting_overlap | overlap | 25.2 | 3/1 | 25.00 | 17.19 | 34.38 | 33.45 | 74.82 | 0.0211 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c39_v0_meeting_overlap | overlap | 26.0 | 4/3 | 56.92 | 30.77 | 52.31 | 47.20 | 57.94 | 0.0181 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c40_v0_call_overlap | overlap | 21.5 | 3/2 | 56.92 | 43.94 | 56.06 | 49.71 | 55.23 | 0.0203 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| ensynth_0001_call_clean | clean | 166.8 | 2/2 | 99.15 | 7.07 | 7.73 | 7.94 | 7.80 | 0.0215 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| ensynth_0003_call_overlap | overlap | 216.8 | 2/2 | 99.38 | 8.40 | 12.98 | 10.82 | 12.09 | 0.0233 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| ensynth_0004_meeting_clean | clean | 158.6 | 3/3 | 97.63 | 2.32 | 2.32 | 6.62 | 6.60 | 0.0222 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| ensynth_0005_call_noise | noise | 125.5 | 2/2 | 97.30 | 18.55 | 22.41 | 14.45 | 15.81 | 0.0197 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| ensynth_0008_meeting_farfield | farfield | 103.1 | 2/2 | 98.16 | 13.38 | 13.38 | 9.46 | 10.94 | 0.0198 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| ensynth_0009_call_phone | phone | 138.9 | 2/2 | 99.55 | 5.51 | 9.03 | 10.52 | 12.20 | 0.0204 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| ensynth_0011_call_clean | clean | 134.4 | 3/3 | 99.09 | 6.09 | 8.13 | 7.88 | 9.25 | 0.0229 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| ensynth_0012_meeting_farfield | farfield | 232.5 | 2/2 | 97.64 | 12.50 | 12.50 | 10.35 | 10.28 | 0.0188 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| ensynth_0013_call_phone | phone | 176.0 | 3/2 | 97.19 | 26.01 | 45.13 | 20.17 | 46.86 | 0.0218 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| mix_0000_meeting_noise | noise | 103.8 | 3/2 | 84.71 | 54.13 | 54.13 | 41.15 | 47.49 | 0.0211 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| mix_0002_meeting_clean | clean | 125.6 | 4/3 | 82.64 | 29.76 | 33.91 | 19.90 | 40.33 | 0.0219 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| mix_0003_call_phone | phone | 122.1 | 3/3 | 92.90 | 74.09 | 74.09 | 57.52 | 55.59 | 0.0194 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| mix_0004_meeting_farfield | farfield | 74.5 | 4/2 | 100.00 | 36.65 | 44.10 | 34.25 | 58.90 | 0.0192 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |

## refpipe-bp-robust-test-bp0.0

tier=medium, platform=desktop, device=Darwin arm64 (arm), threads=2, dataset=refpipe-baseline-test, config_hash=687e156f3354e6d9398fd837dcb195df0b214e2876cd67e705dfd31a733b9b33

### По условиям

| group | files | WER raw | WER norm | WER punct | WER p-sep | WER p-lc | WER num | cpWER | DER | DER ±.25 | JER | spk acc | ts start MAE ms | ts end MAE ms | start<300ms | RTF |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ALL | 31 | 54.46 | 21.74 | 53.81 | 48.81 | 29.87 | 20.60 | 29.85 | 19.79 | 11.69 | 29.64 | 77.42 | 268 | 359 | 74.27 | 0.042 |
| clean | 5 | 57.26 | 19.58 | 56.77 | 52.84 | 27.70 | 19.39 | 21.56 | 13.92 | 7.15 | 17.58 | 100.00 | 223 | 258 | 75.38 | 0.042 |
| farfield | 4 | 32.68 | 15.06 | 31.69 | 26.12 | 21.30 | 12.63 | 21.52 | 12.99 | 6.22 | 24.83 | 75.00 | 192 | 270 | 82.25 | 0.045 |
| noise | 7 | 63.94 | 14.73 | 63.21 | 60.79 | 24.67 | 13.57 | 24.69 | 17.87 | 8.95 | 34.29 | 71.43 | 177 | 236 | 77.62 | 0.038 |
| overlap | 6 | 61.03 | 26.59 | 60.05 | 54.47 | 35.28 | 26.54 | 29.41 | 24.10 | 15.91 | 32.65 | 83.33 | 389 | 558 | 64.23 | 0.043 |
| phone | 6 | 39.82 | 23.88 | 39.72 | 33.54 | 30.31 | 20.92 | 54.29 | 31.41 | 22.24 | 39.74 | 50.00 | 218 | 315 | 74.62 | 0.043 |
| reverb | 3 | 49.74 | 35.69 | 49.40 | 44.04 | 41.37 | 32.93 | 37.59 | 15.03 | 7.79 | 19.03 | 100.00 | 463 | 558 | 73.36 | 0.044 |

### По файлам

| file | condition | dur s | spk ref/hyp | WER raw | WER norm | cpWER | DER | JER | RTF | notes |
|---|---|---|---|---|---|---|---|---|---|---|
| synth_0005_call_clean | clean | 227.3 | 3/3 | 35.86 | 11.19 | 12.08 | 5.92 | 6.05 | 0.0439 | sweep: asr.blank_penalty=0.0 |
| synth_0008_meeting_reverb | reverb | 201.6 | 4/4 | 49.89 | 33.48 | 35.49 | 14.09 | 14.31 | 0.0439 | sweep: asr.blank_penalty=0.0 |
| synth_0009_call_phone | phone | 86.4 | 2/2 | 47.31 | 31.48 | 40.37 | 17.09 | 18.87 | 0.0444 | sweep: asr.blank_penalty=0.0 |
| synth_0020_meeting_overlap | overlap | 114.0 | 4/4 | 33.97 | 13.73 | 18.04 | 11.63 | 20.25 | 0.046 | sweep: asr.blank_penalty=0.0 |
| synth_0021_call_noise | noise | 213.6 | 2/2 | 32.19 | 10.63 | 13.29 | 7.17 | 8.29 | 0.0446 | sweep: asr.blank_penalty=0.0 |
| synth_0022_meeting_farfield | farfield | 209.5 | 2/2 | 32.77 | 11.74 | 15.65 | 8.69 | 9.31 | 0.0459 | sweep: asr.blank_penalty=0.0 |
| synth_0030_meeting_overlap | overlap | 173.4 | 4/4 | 49.73 | 29.22 | 33.51 | 26.31 | 28.52 | 0.0454 | sweep: asr.blank_penalty=0.0 |
| synth_0032_meeting_phone | phone | 99.9 | 2/1 | 29.91 | 10.90 | 101.90 | 54.16 | 75.45 | 0.0435 | sweep: asr.blank_penalty=0.0 |
| synth_0033_call_overlap | overlap | 176.8 | 3/3 | 43.12 | 23.19 | 25.96 | 15.15 | 20.68 | 0.0436 | sweep: asr.blank_penalty=0.0 |
| cats_c03-c05_v1_call_phone | phone | 73.6 | 2/2 | 40.41 | 25.39 | 29.02 | 18.75 | 19.82 | 0.0436 | sweep: asr.blank_penalty=0.0 |
| cats_c03_v0_meeting_farfield | farfield | 53.2 | 2/2 | 45.16 | 27.96 | 31.18 | 16.36 | 16.86 | 0.0432 | sweep: asr.blank_penalty=0.0 |
| cats_c04-c14_v1_meeting_clean | clean | 79.4 | 4/4 | 27.12 | 11.56 | 15.03 | 15.73 | 15.97 | 0.044 | sweep: asr.blank_penalty=0.0 |
| cats_c04_v0_call_overlap | overlap | 30.0 | 4/3 | 50.00 | 32.98 | 43.62 | 39.48 | 53.36 | 0.0463 | sweep: asr.blank_penalty=0.0 |
| cats_c05-c07_v1_call_noise | noise | 52.3 | 2/2 | 41.01 | 25.90 | 26.62 | 26.56 | 25.71 | 0.0439 | sweep: asr.blank_penalty=0.0 |
| cats_c05_v0_meeting_phone | phone | 44.1 | 2/2 | 27.00 | 10.00 | 10.00 | 10.57 | 10.54 | 0.0414 | sweep: asr.blank_penalty=0.0 |
| cats_c12-c15_v1_meeting_overlap | overlap | 46.1 | 2/2 | 44.96 | 29.46 | 31.78 | 41.38 | 41.55 | 0.0448 | sweep: asr.blank_penalty=0.0 |
| cats_c15-c17_v1_call_phone | phone | 47.4 | 2/1 | 39.17 | 23.33 | 85.00 | 49.33 | 70.62 | 0.0428 | sweep: asr.blank_penalty=0.0 |
| cats_c15_v0_meeting_reverb | reverb | 31.5 | 2/2 | 31.34 | 23.88 | 23.88 | 15.16 | 17.04 | 0.0448 | sweep: asr.blank_penalty=0.0 |
| cats_c24_v0_call_noise | noise | 25.9 | 3/2 | 42.10 | 38.67 | 64.00 | 43.17 | 58.45 | 0.0412 | sweep: asr.blank_penalty=0.0 |
| cats_c27_v0_meeting_noise | noise | 27.8 | 2/2 | 49.15 | 37.29 | 86.44 | 75.85 | 74.69 | 0.0452 | sweep: asr.blank_penalty=0.0 |
| cats_c30_v0_call_phone | phone | 31.4 | 4/3 | 54.65 | 45.35 | 46.51 | 30.90 | 43.17 | 0.0441 | sweep: asr.blank_penalty=0.0 |
| cats_c31_v0_meeting_farfield | farfield | 41.6 | 3/2 | 25.32 | 18.99 | 45.57 | 28.32 | 52.51 | 0.0435 | sweep: asr.blank_penalty=0.0 |
| cats_c33_v0_meeting_clean | clean | 27.7 | 3/3 | 11.47 | 3.28 | 27.87 | 23.54 | 29.32 | 0.0479 | sweep: asr.blank_penalty=0.0 |
| cats_c35_v0_meeting_farfield | farfield | 31.1 | 3/3 | 23.08 | 15.38 | 20.00 | 17.34 | 20.65 | 0.0478 | sweep: asr.blank_penalty=0.0 |
| cats_c38_v0_call_reverb | reverb | 26.3 | 2/2 | 67.69 | 63.08 | 66.15 | 23.43 | 25.74 | 0.0461 | sweep: asr.blank_penalty=0.0 |
| ensynth_0000_meeting_clean | clean | 111.6 | 2/2 | 98.10 | 5.88 | 5.88 | 9.04 | 9.16 | 0.0354 | sweep: asr.blank_penalty=0.0 |
| ensynth_0002_meeting_overlap | overlap | 143.5 | 2/2 | 98.85 | 31.26 | 31.58 | 31.78 | 31.55 | 0.0348 | sweep: asr.blank_penalty=0.0 |
| ensynth_0006_meeting_noise | noise | 70.2 | 2/2 | 96.65 | 11.85 | 12.32 | 12.15 | 11.68 | 0.0354 | sweep: asr.blank_penalty=0.0 |
| ensynth_0007_call_noise | noise | 100.5 | 2/2 | 98.85 | 15.79 | 16.07 | 10.74 | 11.07 | 0.0302 | sweep: asr.blank_penalty=0.0 |
| ensynth_0010_meeting_noise | noise | 69.3 | 3/2 | 97.31 | 4.23 | 55.03 | 33.89 | 50.15 | 0.0263 | sweep: asr.blank_penalty=0.0 |
| mix_0001_call_clean | clean | 127.7 | 3/3 | 69.77 | 47.18 | 48.53 | 30.24 | 27.41 | 0.0418 | sweep: asr.blank_penalty=0.0 |

## refpipe-bp-robust-test-bp1.0

tier=medium, platform=desktop, device=Darwin arm64 (arm), threads=2, dataset=refpipe-baseline-test, config_hash=7c3795c7edb4afefa5acd37db59cae5db877dcf80154c5c554247c4602bc8a89

### По условиям

| group | files | WER raw | WER norm | WER punct | WER p-sep | WER p-lc | WER num | cpWER | DER | DER ±.25 | JER | spk acc | ts start MAE ms | ts end MAE ms | start<300ms | RTF |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ALL | 31 | 54.13 | 21.38 | 53.48 | 48.52 | 29.74 | 20.08 | 31.42 | 19.77 | 11.71 | 31.56 | 77.42 | 256 | 346 | 77.06 | 0.046 |
| clean | 5 | 57.32 | 19.70 | 56.83 | 52.69 | 27.86 | 19.39 | 21.38 | 13.57 | 6.88 | 17.29 | 100.00 | 223 | 225 | 75.82 | 0.045 |
| farfield | 4 | 32.68 | 14.92 | 31.69 | 26.91 | 22.09 | 12.48 | 21.95 | 12.14 | 5.16 | 23.59 | 75.00 | 200 | 269 | 83.23 | 0.044 |
| noise | 7 | 64.07 | 14.43 | 63.33 | 60.68 | 24.57 | 13.20 | 24.76 | 17.42 | 8.74 | 34.75 | 71.43 | 170 | 230 | 81.91 | 0.036 |
| overlap | 6 | 60.31 | 26.03 | 59.33 | 54.00 | 34.98 | 25.92 | 31.98 | 24.10 | 16.12 | 35.52 | 83.33 | 377 | 555 | 66.87 | 0.055 |
| phone | 6 | 39.21 | 23.37 | 39.11 | 33.23 | 30.38 | 19.69 | 60.92 | 34.02 | 24.27 | 47.40 | 50.00 | 164 | 301 | 79.34 | 0.043 |
| reverb | 3 | 48.38 | 34.48 | 48.04 | 42.30 | 39.63 | 31.55 | 37.76 | 13.66 | 6.95 | 18.92 | 100.00 | 459 | 528 | 75.44 | 0.057 |

### По файлам

| file | condition | dur s | spk ref/hyp | WER raw | WER norm | cpWER | DER | JER | RTF | notes |
|---|---|---|---|---|---|---|---|---|---|---|
| synth_0005_call_clean | clean | 227.3 | 3/3 | 35.17 | 11.19 | 12.26 | 5.83 | 5.85 | 0.0447 | sweep: asr.blank_penalty=1.0 |
| synth_0008_meeting_reverb | reverb | 201.6 | 4/4 | 48.79 | 32.14 | 35.27 | 12.18 | 12.58 | 0.0613 | sweep: asr.blank_penalty=1.0 |
| synth_0009_call_phone | phone | 86.4 | 2/2 | 45.52 | 29.26 | 38.89 | 15.38 | 17.89 | 0.0439 | sweep: asr.blank_penalty=1.0 |
| synth_0020_meeting_overlap | overlap | 114.0 | 4/4 | 33.59 | 13.73 | 15.29 | 11.72 | 19.61 | 0.0628 | sweep: asr.blank_penalty=1.0 |
| synth_0021_call_noise | noise | 213.6 | 2/2 | 33.17 | 11.63 | 14.45 | 6.34 | 7.70 | 0.0444 | sweep: asr.blank_penalty=1.0 |
| synth_0022_meeting_farfield | farfield | 209.5 | 2/2 | 32.98 | 11.52 | 16.09 | 8.51 | 9.81 | 0.045 | sweep: asr.blank_penalty=1.0 |
| synth_0030_meeting_overlap | overlap | 173.4 | 4/4 | 48.94 | 26.00 | 32.44 | 23.77 | 26.71 | 0.0629 | sweep: asr.blank_penalty=1.0 |
| synth_0032_meeting_phone | phone | 99.9 | 2/1 | 30.37 | 10.90 | 101.90 | 54.16 | 75.45 | 0.0435 | sweep: asr.blank_penalty=1.0 |
| synth_0033_call_overlap | overlap | 176.8 | 3/3 | 42.92 | 23.62 | 26.17 | 14.37 | 19.80 | 0.0609 | sweep: asr.blank_penalty=1.0 |
| cats_c03-c05_v1_call_phone | phone | 73.6 | 2/2 | 39.38 | 24.35 | 28.50 | 17.90 | 19.76 | 0.0431 | sweep: asr.blank_penalty=1.0 |
| cats_c03_v0_meeting_farfield | farfield | 53.2 | 2/2 | 47.31 | 30.11 | 29.03 | 14.41 | 14.78 | 0.0432 | sweep: asr.blank_penalty=1.0 |
| cats_c04-c14_v1_meeting_clean | clean | 79.4 | 4/4 | 26.55 | 11.56 | 15.03 | 14.82 | 15.20 | 0.0446 | sweep: asr.blank_penalty=1.0 |
| cats_c04_v0_call_overlap | overlap | 30.0 | 4/2 | 47.87 | 32.98 | 74.47 | 51.66 | 68.13 | 0.0639 | sweep: asr.blank_penalty=1.0 |
| cats_c05-c07_v1_call_noise | noise | 52.3 | 2/2 | 38.85 | 19.42 | 23.02 | 23.92 | 23.97 | 0.0443 | sweep: asr.blank_penalty=1.0 |
| cats_c05_v0_meeting_phone | phone | 44.1 | 2/2 | 34.00 | 14.00 | 14.00 | 10.36 | 10.33 | 0.0417 | sweep: asr.blank_penalty=1.0 |
| cats_c12-c15_v1_meeting_overlap | overlap | 46.1 | 2/2 | 39.53 | 28.68 | 55.81 | 46.18 | 47.34 | 0.0616 | sweep: asr.blank_penalty=1.0 |
| cats_c15-c17_v1_call_phone | phone | 47.4 | 2/1 | 35.00 | 24.17 | 85.83 | 49.33 | 70.59 | 0.0433 | sweep: asr.blank_penalty=1.0 |
| cats_c15_v0_meeting_reverb | reverb | 31.5 | 2/2 | 28.36 | 23.88 | 23.88 | 16.80 | 18.24 | 0.0447 | sweep: asr.blank_penalty=1.0 |
| cats_c24_v0_call_noise | noise | 25.9 | 3/2 | 42.10 | 37.33 | 65.33 | 44.12 | 59.84 | 0.0415 | sweep: asr.blank_penalty=1.0 |
| cats_c27_v0_meeting_noise | noise | 27.8 | 2/2 | 47.46 | 35.59 | 83.05 | 77.85 | 78.83 | 0.0455 | sweep: asr.blank_penalty=1.0 |
| cats_c30_v0_call_phone | phone | 31.4 | 4/1 | 52.33 | 43.02 | 122.09 | 69.18 | 90.38 | 0.0435 | sweep: asr.blank_penalty=1.0 |
| cats_c31_v0_meeting_farfield | farfield | 41.6 | 3/2 | 22.79 | 13.92 | 50.63 | 27.24 | 54.84 | 0.0429 | sweep: asr.blank_penalty=1.0 |
| cats_c33_v0_meeting_clean | clean | 27.7 | 3/3 | 11.47 | 3.28 | 27.87 | 23.54 | 29.38 | 0.0467 | sweep: asr.blank_penalty=1.0 |
| cats_c35_v0_meeting_farfield | farfield | 31.1 | 3/3 | 21.54 | 18.46 | 18.46 | 13.79 | 14.95 | 0.0452 | sweep: asr.blank_penalty=1.0 |
| cats_c38_v0_call_reverb | reverb | 26.3 | 2/2 | 66.15 | 61.54 | 69.23 | 23.62 | 25.93 | 0.0412 | sweep: asr.blank_penalty=1.0 |
| ensynth_0000_meeting_clean | clean | 111.6 | 2/2 | 98.10 | 5.88 | 5.88 | 9.04 | 9.16 | 0.0277 | sweep: asr.blank_penalty=1.0 |
| ensynth_0002_meeting_overlap | overlap | 143.5 | 2/2 | 98.85 | 31.26 | 31.58 | 31.78 | 31.55 | 0.0281 | sweep: asr.blank_penalty=1.0 |
| ensynth_0006_meeting_noise | noise | 70.2 | 2/2 | 96.65 | 11.85 | 12.32 | 12.15 | 11.68 | 0.0268 | sweep: asr.blank_penalty=1.0 |
| ensynth_0007_call_noise | noise | 100.5 | 2/2 | 98.85 | 15.79 | 16.07 | 10.74 | 11.07 | 0.0268 | sweep: asr.blank_penalty=1.0 |
| ensynth_0010_meeting_noise | noise | 69.3 | 3/2 | 97.31 | 4.23 | 55.03 | 33.89 | 50.15 | 0.0245 | sweep: asr.blank_penalty=1.0 |
| mix_0001_call_clean | clean | 127.7 | 3/3 | 71.14 | 47.63 | 47.63 | 29.35 | 26.85 | 0.0587 | sweep: asr.blank_penalty=1.0 |

## refpipe-bp-robust-test-bp1.0-k1thr030

tier=medium, platform=desktop, device=Darwin arm64 (arm), threads=2, dataset=refpipe-baseline-test, config_hash=83001925386508712225eecf7f12f206f082ea973b9e24f1c8ae8eed732031ff

### По условиям

| group | files | WER raw | WER norm | WER punct | WER p-sep | WER p-lc | WER num | cpWER | DER | DER ±.25 | JER | spk acc | ts start MAE ms | ts end MAE ms | start<300ms | RTF |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ALL | 31 | 54.13 | 21.38 | 53.48 | 48.52 | 29.74 | 20.08 | 30.54 | 19.29 | 11.29 | 29.75 | 80.65 | 256 | 346 | 77.06 | 0.020 |
| clean | 5 | 57.32 | 19.70 | 56.83 | 52.69 | 27.86 | 19.39 | 21.38 | 13.57 | 6.88 | 17.29 | 100.00 | 223 | 225 | 75.82 | 0.023 |
| farfield | 4 | 32.68 | 14.92 | 31.69 | 26.91 | 22.09 | 12.48 | 21.95 | 12.14 | 5.16 | 23.59 | 75.00 | 200 | 269 | 83.23 | 0.013 |
| noise | 7 | 64.07 | 14.43 | 63.33 | 60.68 | 24.57 | 13.20 | 24.76 | 17.42 | 8.74 | 34.75 | 71.43 | 170 | 230 | 81.91 | 0.023 |
| overlap | 6 | 60.31 | 26.03 | 59.33 | 54.00 | 34.98 | 25.92 | 31.98 | 24.10 | 16.12 | 35.52 | 83.33 | 377 | 555 | 66.87 | 0.021 |
| phone | 6 | 39.21 | 23.37 | 39.11 | 33.23 | 30.38 | 19.69 | 54.29 | 30.42 | 21.00 | 38.06 | 66.67 | 164 | 301 | 79.34 | 0.019 |
| reverb | 3 | 48.38 | 34.48 | 48.04 | 42.30 | 39.63 | 31.55 | 37.76 | 13.66 | 6.95 | 18.92 | 100.00 | 459 | 528 | 75.44 | 0.011 |

### По файлам

| file | condition | dur s | spk ref/hyp | WER raw | WER norm | cpWER | DER | JER | RTF | notes |
|---|---|---|---|---|---|---|---|---|---|---|
| synth_0005_call_clean | clean | 227.3 | 3/3 | 35.17 | 11.19 | 12.26 | 5.83 | 5.85 | 0.0244 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0008_meeting_reverb | reverb | 201.6 | 4/4 | 48.79 | 32.14 | 35.27 | 12.18 | 12.58 | 0.0133 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0009_call_phone | phone | 86.4 | 2/2 | 45.52 | 29.26 | 38.89 | 15.38 | 17.89 | 0.0217 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0020_meeting_overlap | overlap | 114.0 | 4/4 | 33.59 | 13.73 | 15.29 | 11.72 | 19.61 | 0.0256 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0021_call_noise | noise | 213.6 | 2/2 | 33.17 | 11.63 | 14.45 | 6.34 | 7.70 | 0.0244 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0022_meeting_farfield | farfield | 209.5 | 2/2 | 32.98 | 11.52 | 16.09 | 8.51 | 9.81 | 0.0121 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0030_meeting_overlap | overlap | 173.4 | 4/4 | 48.94 | 26.00 | 32.44 | 23.77 | 26.71 | 0.0212 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0032_meeting_phone | phone | 99.9 | 2/1 | 30.37 | 10.90 | 101.90 | 54.16 | 75.45 | 0.0162 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| synth_0033_call_overlap | overlap | 176.8 | 3/3 | 42.92 | 23.62 | 26.17 | 14.37 | 19.80 | 0.0234 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c03-c05_v1_call_phone | phone | 73.6 | 2/2 | 39.38 | 24.35 | 28.50 | 17.90 | 19.76 | 0.0203 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c03_v0_meeting_farfield | farfield | 53.2 | 2/2 | 47.31 | 30.11 | 29.03 | 14.41 | 14.78 | 0.0163 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c04-c14_v1_meeting_clean | clean | 79.4 | 4/4 | 26.55 | 11.56 | 15.03 | 14.82 | 15.20 | 0.0201 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c04_v0_call_overlap | overlap | 30.0 | 4/2 | 47.87 | 32.98 | 74.47 | 51.66 | 68.13 | 0.0204 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c05-c07_v1_call_noise | noise | 52.3 | 2/2 | 38.85 | 19.42 | 23.02 | 23.92 | 23.97 | 0.0212 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c05_v0_meeting_phone | phone | 44.1 | 2/2 | 34.00 | 14.00 | 14.00 | 10.36 | 10.33 | 0.0203 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c12-c15_v1_meeting_overlap | overlap | 46.1 | 2/2 | 39.53 | 28.68 | 55.81 | 46.18 | 47.34 | 0.0157 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c15-c17_v1_call_phone | phone | 47.4 | 2/1 | 35.00 | 24.17 | 85.83 | 49.33 | 70.59 | 0.0182 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c15_v0_meeting_reverb | reverb | 31.5 | 2/2 | 28.36 | 23.88 | 23.88 | 16.80 | 18.24 | 0.0037 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c24_v0_call_noise | noise | 25.9 | 3/2 | 42.10 | 37.33 | 65.33 | 44.12 | 59.84 | 0.0221 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c27_v0_meeting_noise | noise | 27.8 | 2/2 | 47.46 | 35.59 | 83.05 | 77.85 | 78.83 | 0.0203 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c30_v0_call_phone | phone | 31.4 | 4/4 | 52.33 | 43.02 | 46.51 | 26.05 | 34.34 | 0.02 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c31_v0_meeting_farfield | farfield | 41.6 | 3/2 | 22.79 | 13.92 | 50.63 | 27.24 | 54.84 | 0.0171 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c33_v0_meeting_clean | clean | 27.7 | 3/3 | 11.47 | 3.28 | 27.87 | 23.54 | 29.38 | 0.0194 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c35_v0_meeting_farfield | farfield | 31.1 | 3/3 | 21.54 | 18.46 | 18.46 | 13.79 | 14.95 | 0.0102 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| cats_c38_v0_call_reverb | reverb | 26.3 | 2/2 | 66.15 | 61.54 | 69.23 | 23.62 | 25.93 | 0.0069 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| ensynth_0000_meeting_clean | clean | 111.6 | 2/2 | 98.10 | 5.88 | 5.88 | 9.04 | 9.16 | 0.0219 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| ensynth_0002_meeting_overlap | overlap | 143.5 | 2/2 | 98.85 | 31.26 | 31.58 | 31.78 | 31.55 | 0.0178 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| ensynth_0006_meeting_noise | noise | 70.2 | 2/2 | 96.65 | 11.85 | 12.32 | 12.15 | 11.68 | 0.0216 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| ensynth_0007_call_noise | noise | 100.5 | 2/2 | 98.85 | 15.79 | 16.07 | 10.74 | 11.07 | 0.0235 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| ensynth_0010_meeting_noise | noise | 69.3 | 3/2 | 97.31 | 4.23 | 55.03 | 33.89 | 50.15 | 0.0221 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |
| mix_0001_call_clean | clean | 127.7 | 3/3 | 71.14 | 47.63 | 47.63 | 29.35 | 26.85 | 0.0226 | sweep: asr.blank_penalty=1.0 diarization.relabel.k1_silhouette_thr=0.30 |

