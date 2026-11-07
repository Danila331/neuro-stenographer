#!/usr/bin/env bash
# Хостовая (macOS/Linux) сборка JNI-обёртки llama.cpp -- для проверки LLM-кода с реальной GGUF без телефона/эмулятора
# (используется tools/jvm-smoke). Нужны: source scripts/env.sh (JAVA_HOME, cmake/ninja из $ROOT/.local/bin) и
# исходники llama.cpp (scripts/fetch_llama_cpp.sh). Результат: $ROOT/.cache/android-deps/llm-host-build/libneurosteno_llm.*
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$HERE/.." && pwd)"
: "${NEUROSTENO_ROOT:?Выполните: source scripts/env.sh}"
: "${JAVA_HOME:?Выполните: source scripts/env.sh}"
command -v cmake >/dev/null || { echo "нет cmake в PATH (см. README: pip-колесо cmake в \$ROOT/.local)"; exit 1; }

"$HERE/fetch_llama_cpp.sh" >/dev/null
# LLM_HOST_BUILD -- другой каталог сборки (у каждой рабочей копии свой: кэш CMake привязан к пути исходников)
OUT="${LLM_HOST_BUILD:-$NEUROSTENO_ROOT/.cache/android-deps/llm-host-build}"
mkdir -p "$OUT"
GEN=()
command -v ninja >/dev/null && GEN=(-G Ninja)
cmake -S "$ANDROID_DIR/engine-llm/src/main/cpp" -B "$OUT" "${GEN[@]}" \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=ON -DGGML_METAL=OFF -DGGML_BLAS=OFF
cmake --build "$OUT" --target neurosteno_llm --parallel "${JOBS:-3}"
ls -la "$OUT"/libneurosteno_llm.*
