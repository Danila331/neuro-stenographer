#!/usr/bin/env bash
# Скачивает кандидатов ASR из официальных релизов k2-fsa/sherpa-onnx (asr-models) в общий кэш.
# Атомарно: .tmp-asr-bench-<name>/ -> mv в <name>/. Если папка уже есть — пропускает.
# Аргументы: имена архивов (без .tar.bz2); без аргументов — весь список кандидатов.
# Можно запускать несколько воркеров параллельно (замок .lock в tmp-папке).
set -uo pipefail
ROOT="${ROOT:-/Users/danilka2203/Desktop/work/neuro-stenographer-claude}"
CACHE="$ROOT/.cache/models"
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
mkdir -p "$CACHE"
NAMES=("$@")
if [ ${#NAMES[@]} -eq 0 ]; then
NAMES=(
 sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19
 sherpa-onnx-nemo-transducer-giga-am-v2-russian-2025-04-19
 sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16
 sherpa-onnx-nemo-transducer-giga-am-v3-russian-2025-12-16
 sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16
 sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16
 sherpa-onnx-zipformer-ru-int8-2025-04-20
 sherpa-onnx-zipformer-ru-2025-04-20
 sherpa-onnx-small-zipformer-ru-2024-09-18
 sherpa-onnx-streaming-t-one-russian-2025-09-08
 sherpa-onnx-whisper-turbo
 sherpa-onnx-whisper-small
 sherpa-onnx-whisper-medium
 sherpa-onnx-whisper-large-v3
)
fi
for n in "${NAMES[@]}"; do
  if [ -d "$CACHE/$n" ]; then echo "have $n"; continue; fi
  T="$CACHE/.tmp-asr-bench-$n"
  mkdir -p "$T"
  mkdir "$T/.lock" 2>/dev/null || { echo "busy $n"; continue; }   # другой воркер уже качает
  echo "fetch $n"
  if [ ! -f "$T/done" ]; then
    curl -fL -sS -C - --retry 5 -o "$T/a.tar.bz2" "$BASE/$n.tar.bz2" || [ -s "$T/a.tar.bz2" ] || { echo "FAIL $n"; rmdir "$T/.lock"; continue; }
  fi
  rm -rf "$T/x"; mkdir "$T/x"
  if tar -xjf "$T/a.tar.bz2" -C "$T/x"; then
    if [ -d "$T/x/$n" ]; then mv "$T/x/$n" "$CACHE/$n"; else mv "$T/x" "$CACHE/$n"; fi
    rm -rf "$T"; echo "ok $n"
  else
    echo "TAR FAIL $n (partial?)"; rmdir "$T/.lock"
  fi
done
