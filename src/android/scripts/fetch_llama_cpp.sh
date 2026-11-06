#!/usr/bin/env bash
# Скачивает исходники llama.cpp ЗАФИКСИРОВАННОГО тега (официальный GitHub ggml-org/llama.cpp), проверяет sha256 и
# распаковывает в engine-llm/src/main/cpp/llama.cpp (папка в .gitignore). Кэш: $ROOT/.cache/android-deps/llama.cpp-<тег>/.
set -euo pipefail

TAG="v0.9.0"
SHA256="5d0f1aaf55fd8629c29a55e81b7cb63b80239f83c33a580963071204896836a8"
URL="https://github.com/ggml-org/llama.cpp/archive/refs/tags/${TAG}.tar.gz"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$HERE/.." && pwd)"
COMMON="$(git -C "$HERE" rev-parse --path-format=absolute --git-common-dir)"
ROOT="$(dirname "$COMMON")"
CACHE="$ROOT/.cache/android-deps/llama.cpp-${TAG}"
DEST="$ANDROID_DIR/engine-llm/src/main/cpp/llama.cpp"
TAR="$CACHE/llama.cpp-${TAG}.tar.gz"
mkdir -p "$CACHE"

sha256_of() { shasum -a 256 "$1" | awk '{print $1}'; }

if [ ! -f "$TAR" ] || [ "$(sha256_of "$TAR")" != "$SHA256" ]; then
  for attempt in 1 2 3 4 5 6; do
    curl -fL --silent --show-error -C - --max-time 1500 -o "$CACHE/.part" "$URL" && break
    echo "повтор загрузки ($attempt)..." >&2; sleep 3
  done
  got="$(sha256_of "$CACHE/.part")"
  if [ "$got" != "$SHA256" ]; then
    echo "ОШИБКА: sha256 не совпал (получено $got, ожидалось $SHA256)" >&2; rm -f "$CACHE/.part"; exit 1
  fi
  mv "$CACHE/.part" "$TAR"
fi

if [ -f "$DEST/CMakeLists.txt" ] && [ "$(cat "$DEST/.neurosteno-tag" 2>/dev/null)" = "$TAG" ]; then
  echo "llama.cpp $TAG уже распакован: $DEST"; exit 0
fi
rm -rf "$DEST"
mkdir -p "$DEST"
tar -xzf "$TAR" -C "$DEST" --strip-components=1
echo "$TAG" > "$DEST/.neurosteno-tag"
echo "llama.cpp $TAG распакован в $DEST"
