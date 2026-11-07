#!/usr/bin/env bash
# Хостовая (macOS/Linux) сборка JNI эмбеддера поиска (engine-embed/src/main/cpp/embed_jni.cpp) + libonnxruntime.dylib из sherpa-onnx --
# для тестов OrtTextEmbedder и проверки качества поиска на ПК без эмулятора. Нужны: source scripts/env.sh (JAVA_HOME, cmake),
# scripts/fetch_sherpa_onnx.sh (jar с macOS-библиотеками). Результат: $ROOT/.cache/android-deps/embed-host-build-<хэш исходников>/{libneurosteno_embed.*, libonnxruntime.dylib}
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$HERE/.." && pwd)"
: "${NEUROSTENO_ROOT:?Выполните: source scripts/env.sh}"
: "${JAVA_HOME:?Выполните: source scripts/env.sh}"
command -v cmake >/dev/null || { echo "нет cmake в PATH (source scripts/env.sh)"; exit 1; }

# Каталог по умолчанию называется по хэшу исходников JNI (embed_jni.cpp + CMakeLists.txt): библиотека, собранная из других
# исходников, лежит в другом каталоге и тест её не подхватит (раньше устаревшая сборка давала ложные падения). Тот же хэш считает HostOrt.kt.
SRC="$ANDROID_DIR/engine-embed/src/main/cpp"
HASH="$(cat "$SRC/embed_jni.cpp" "$SRC/CMakeLists.txt" | shasum -a 256 | cut -c1-12)"
OUT="${EMBED_HOST_BUILD:-$NEUROSTENO_ROOT/.cache/android-deps/embed-host-build-$HASH}"
mkdir -p "$OUT"
GEN=()
command -v ninja >/dev/null && GEN=(-G Ninja)
cmake -S "$ANDROID_DIR/engine-embed/src/main/cpp" -B "$OUT/build" "${GEN[@]}" -DCMAKE_BUILD_TYPE=Release \
  -DJAVA_HOME="$JAVA_HOME" -DJAVA_INCLUDE_PATH="$JAVA_HOME/include" -DJAVA_INCLUDE_PATH2="$JAVA_HOME/include/$(uname -s | tr '[:upper:]' '[:lower:]')" \
  -DJAVA_AWT_INCLUDE_PATH="$JAVA_HOME/include" -DJAVA_JVM_LIBRARY="$JAVA_HOME/lib/server/libjvm.dylib" -DJAVA_AWT_LIBRARY="$JAVA_HOME/lib/libjawt.dylib"
cmake --build "$OUT/build" --parallel "${JOBS:-3}"
cp -f "$OUT"/build/libneurosteno_embed.* "$OUT/"
JAR="$NEUROSTENO_ROOT/.cache/android-deps/sherpa-onnx-2.0.1/sherpa-onnx-native-lib-osx-aarch64-2.0.1.jar"
if [ -f "$JAR" ] && [ "$(uname -s)" = Darwin ]; then
  unzip -q -o -j "$JAR" 'sherpa-onnx/native/osx-aarch64/libonnxruntime.dylib' -d "$OUT"
fi
echo "$HASH" > "$OUT/source.hash"
ls -la "$OUT"/*.dylib "$OUT"/*.so 2>/dev/null
