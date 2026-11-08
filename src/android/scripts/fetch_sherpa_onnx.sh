#!/usr/bin/env bash
# Скачивает prebuilt AAR sherpa-onnx (официальный релиз k2-fsa/sherpa-onnx на GitHub), проверяет sha256 и
# публикует его в локальный Maven-репозиторий src/android/third_party/maven (в git не попадает).
# Кэш скачанного: $ROOT/.cache/android-deps/sherpa-onnx-<версия>/ (общий для рабочих копий).
#
#   scripts/fetch_sherpa_onnx.sh               # AAR для Android
#   scripts/fetch_sherpa_onnx.sh --host-native # + dylib'ы для macOS arm64 (нужны только для tools/jvm-smoke)
#
# Версию и контрольные суммы менять здесь и в gradle/libs.versions.toml (sherpa-onnx) одновременно.
set -euo pipefail

VERSION="2.0.1"
AAR_SHA256="a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
NATIVE_OSX_SHA256="718a4ea4799341146ef9a0cc15daa70e6622ae4a16911952604035f396703499"
BASE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$HERE/.." && pwd)"
COMMON="$(git -C "$HERE" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
if [ -n "$COMMON" ]; then ROOT="$(dirname "$COMMON")"; else ROOT="$(cd "$HERE/../../.." && pwd)"; fi
CACHE="$ROOT/.cache/android-deps/sherpa-onnx-${VERSION}"
MAVEN="$ANDROID_DIR/third_party/maven/com/k2fsa/sherpa/onnx/sherpa-onnx/${VERSION}"
mkdir -p "$CACHE"

sha256_of() { shasum -a 256 "$1" | awk '{print $1}'; }

fetch() { # <имя файла> <sha256>
  local name="$1" want="$2" dst="$CACHE/$1"
  if [ -f "$dst" ] && [ "$(sha256_of "$dst")" = "$want" ]; then
    echo "ok (из кэша): $name"; return 0
  fi
  local part="$CACHE/.$name.part"
  for attempt in 1 2 3 4 5 6; do
    curl -fL --silent --show-error -C - --max-time 1500 -o "$part" "$BASE_URL/$name" && break
    echo "повтор загрузки $name ($attempt)..." >&2; sleep 3
  done
  local got; got="$(sha256_of "$part")"
  if [ "$got" != "$want" ]; then
    echo "ОШИБКА: sha256 $name не совпал (получено $got, ожидалось $want)" >&2
    rm -f "$part"; exit 1
  fi
  mv "$part" "$dst"
  echo "скачано и проверено: $name"
}

fetch "sherpa-onnx-${VERSION}.aar" "$AAR_SHA256"

mkdir -p "$MAVEN"
cp "$CACHE/sherpa-onnx-${VERSION}.aar" "$MAVEN/sherpa-onnx-${VERSION}.aar"
cat > "$MAVEN/sherpa-onnx-${VERSION}.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.k2fsa.sherpa.onnx</groupId>
  <artifactId>sherpa-onnx</artifactId>
  <version>${VERSION}</version>
  <packaging>aar</packaging>
</project>
POM
echo "опубликовано в локальный Maven: $MAVEN"

if [ "${1:-}" = "--host-native" ]; then
  fetch "sherpa-onnx-native-lib-osx-aarch64-${VERSION}.jar" "$NATIVE_OSX_SHA256"
fi
