#!/usr/bin/env bash
# Прогон РЕАЛЬНОГО пайплайна на JVM хоста (macOS arm64) с адаптерами sherpa-onnx и моделями из $ROOT/.cache/models --
# проверка без Android SDK.  Опционально LLM-пунктуация: SMOKE_LLM_GGUF=/путь.gguf (после scripts/build_host_jni.sh).
#   src/android/scripts/jvm_smoke.sh <audio.wav> [lite|medium|hard]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../../scripts/env.sh
source "$HERE/../../../scripts/env.sh"
WAV="${1:?usage: $0 <audio.wav> [tier]}"
TIER="${2:-lite}"
"$HERE/fetch_sherpa_onnx.sh" --host-native >/dev/null
cd "$HERE/../tools/jvm-smoke"
../../gradlew -p . run --args="$(cd "$(dirname "$WAV")" && pwd)/$(basename "$WAV") $(cd "$HERE/../../.." && pwd)/config/tiers/$TIER.json $NEUROSTENO_ROOT/.cache/models"
