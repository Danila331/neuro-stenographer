#!/usr/bin/env bash
# Устанавливает пакеты Android SDK в $ROOT/.local/android-sdk. ЛИЦЕНЗИИ скрипт НЕ принимает: пользователь делает это сам
# один раз:   source scripts/env.sh && sdkmanager --licenses
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../../scripts/env.sh
source "$HERE/../../../scripts/env.sh"

[ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ] || { echo "нет cmdline-tools в $ANDROID_HOME/cmdline-tools/latest"; exit 1; }
if [ ! -f "$ANDROID_HOME/licenses/android-sdk-license" ]; then
  echo "Лицензии Android SDK ещё не приняты. Прочитайте и примите их сами:" >&2
  echo "    source scripts/env.sh && sdkmanager --licenses" >&2
  exit 1
fi

PACKAGES=(
  "platform-tools"
  "platforms;android-36"                       # compileSdk
  "platforms;android-35"                       # targetSdk
  "build-tools;36.0.0"
  "ndk;28.2.13676358"                          # default NDK для AGP 9.5
  "cmake;3.22.1"
  "emulator"
  "system-images;android-35;google_apis;arm64-v8a"
)
sdkmanager "${PACKAGES[@]}"
echo "готово. Дальше: src/android/scripts/create_avds.sh"
