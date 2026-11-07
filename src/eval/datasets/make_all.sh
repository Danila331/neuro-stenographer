#!/usr/bin/env bash
# Сборка всех eval-датасетов в $ROOT/.cache/data/.
#   ./make_all.sh                      # полный объём (≈600 фраз, 36 диалогов synth, 60 tts + английские наборы и ru-en-mixed)
#   ./make_all.sh --quick              # быстрая версия (≈100 фраз, 10 диалогов synth, 12 tts)
#   ./make_all.sh --n-asr 800 --n-synth 60 --n-tts 80 --seed 1
#   ./make_all.sh --skip-fetch         # не качать исходники (если уже в .cache/data/.raw)
# Детерминировано: результат зависит только от --seed и содержимого .raw.
set -euo pipefail
cd "$(dirname "$0")"
N_ASR=600; N_SYNTH=36; N_TTS=60; SEED=0; FETCH=1; EXTRA=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --quick) N_ASR=100; N_SYNTH=10; N_TTS=12; EXTRA+=(--n-en-asr 60 --n-en-synth 4 --n-mixed 2) ;;
    --n-asr) N_ASR="$2"; shift ;;
    --n-synth) N_SYNTH="$2"; shift ;;
    --n-tts) N_TTS="$2"; shift ;;
    --seed) SEED="$2"; shift ;;
    --skip-fetch) FETCH=0 ;;
    --no-say) EXTRA+=(--no-say) ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
  shift
done
if [[ ! -x .venv/bin/python ]]; then
  uv venv --python 3.11 .venv
  uv pip install --python .venv/bin/python -e .
fi
PY=.venv/bin/python
if [[ $FETCH == 1 ]]; then $PY -m stenodata fetch ruls fleurs fleurs_en librispeech golos voxforge musan esc50 piper; fi
$PY -m stenodata build all --seed "$SEED" --n-asr "$N_ASR" --n-synth "$N_SYNTH" --n-tts "$N_TTS" ${EXTRA[@]+"${EXTRA[@]}"}
$PY -m stenodata validate
$PY -m stenodata stats > /dev/null
echo "готово: $($PY -c 'from stenodata.common import data_root; print(data_root())')"
