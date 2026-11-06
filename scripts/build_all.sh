#!/usr/bin/env bash
# Сборка release-APK вариантов lite/medium/hard (+ online) с подписью ЛОКАЛЬНЫМ ключом.
# Ключ генерируется один раз в $ROOT/.local/keystore (вне git); результат: artifacts/apk/neurosteno-<tier>-<версия>.apk
#
#   scripts/build_all.sh                    # lite medium hard online
#   scripts/build_all.sh medium             # только medium
#   NO_STAGE=1 scripts/build_all.sh lite    # не перекладывать модели (уже лежат в assets)
#   ALLOW_MISSING_MODELS=1 scripts/build_all.sh   # собрать даже при неполном наборе моделей (для проверки сборки)
#   STAGE_EXTRA='--skip-llm' scripts/build_all.sh lite   # доп. аргументы для stage_models.sh
#
# Требует готового Android SDK (scripts/../src/android/scripts/sdk_setup.sh) и исходников llama.cpp / AAR sherpa-onnx
# (скрипт скачивает их сам через fetch_*.sh).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "$HERE/env.sh"
REPO="$(cd "$HERE/.." && pwd)"
ANDROID_DIR="$REPO/src/android"
OUT="$REPO/artifacts/apk"
KEY_DIR="$ROOT/.local/keystore"
KEYSTORE="$KEY_DIR/neurosteno-release.jks"
KEYPROPS="$KEY_DIR/keystore.properties"

TIERS=("$@")
[ "${#TIERS[@]}" -gt 0 ] || TIERS=(lite medium hard online)

# --- проверки окружения ---
[ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ] || { echo "нет Android command-line tools (см. src/android/README.md)"; exit 1; }
[ -d "$ANDROID_HOME/platforms/android-36" ] || { echo "нет platforms;android-36 -- выполните src/android/scripts/sdk_setup.sh"; exit 1; }
command -v java >/dev/null || { echo "нет JDK 17 (source scripts/env.sh)"; exit 1; }

# --- локальный ключ подписи (создаётся один раз; в git не попадает: .local/ в .gitignore) ---
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$KEY_DIR"; chmod 700 "$KEY_DIR"
  PASS="$(openssl rand -hex 16)"
  keytool -genkeypair -v -keystore "$KEYSTORE" -storetype PKCS12 -alias neurosteno \
    -keyalg RSA -keysize 4096 -validity 36500 -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Neurosteno Local, OU=Hackathon, O=Neurosteno, C=RU" >/dev/null 2>&1
  printf 'storePassword=%s\nkeyAlias=neurosteno\n' "$PASS" > "$KEYPROPS"
  chmod 600 "$KEYSTORE" "$KEYPROPS"
  echo "создан локальный ключ подписи: $KEYSTORE"
fi
STORE_PASS="$(sed -n 's/^storePassword=//p' "$KEYPROPS")"

"$ANDROID_DIR/scripts/fetch_sherpa_onnx.sh"
"$ANDROID_DIR/scripts/fetch_llama_cpp.sh"

mkdir -p "$OUT"
cd "$ANDROID_DIR"
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"

for tier in "${TIERS[@]}"; do
  echo "=== $tier ==="
  if [ -z "${NO_STAGE:-}" ]; then
    # STAGE_EXTRA -- дополнительные аргументы stage_models.sh (например, "--map a=b")
    # shellcheck disable=SC2086
    if [ -n "${ALLOW_MISSING_MODELS:-}" ]; then "$HERE/stage_models.sh" "$tier" --allow-missing ${STAGE_EXTRA:-}
    else "$HERE/stage_models.sh" "$tier" ${STAGE_EXTRA:-}; fi
  fi
  cap="$(printf '%s' "${tier:0:1}" | tr '[:lower:]' '[:upper:]')${tier:1}"
  ./gradlew ":app:assemble${cap}Release" -Pneurosteno.android=true \
    -Pneurosteno.keystore="$KEYSTORE" -Pneurosteno.keystorePassword="$STORE_PASS" -Pneurosteno.keyAlias=neurosteno
  APK="$(find "app/build/outputs/apk/$tier/release" -name '*.apk' | head -1)"
  [ -n "$APK" ] || { echo "APK для $tier не найден"; exit 1; }
  DEST="$OUT/neurosteno-$tier-$VERSION.apk"
  cp "$APK" "$DEST"
  # проверка подписи, если доступен apksigner
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -name apksigner -type f 2>/dev/null | sort | tail -1 || true)"
  [ -n "$APKSIGNER" ] && "$APKSIGNER" verify "$DEST" && echo "подпись проверена"
  echo "$tier: $(du -h "$DEST" | cut -f1)  sha256 $(shasum -a 256 "$DEST" | cut -d' ' -f1)"
done

echo "готово. APK в $OUT"
"$ANDROID_DIR/gradlew" --stop >/dev/null 2>&1 || true
