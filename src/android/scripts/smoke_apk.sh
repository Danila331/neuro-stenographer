#!/usr/bin/env bash
# Смоук-проверка APK на запущенном эмуляторе/устройстве: установка, запуск главной Activity, logcat без FATAL.
#   src/android/scripts/smoke_apk.sh <apk> [wav-для-импорта]
# Эмулятор поднимайте scripts/emulator.sh <avd>; после проверки гасите: scripts/emulator.sh stop.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../../scripts/env.sh
source "$HERE/../../../scripts/env.sh"

APK="${1:?usage: $0 <apk> [audio-file]}"
AUDIO="${2:-}"
[ -f "$APK" ] || { echo "нет файла $APK" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "нет подключённого устройства/эмулятора (scripts/emulator.sh <avd>)" >&2; exit 1; }

PKG="$("$ANDROID_HOME"/build-tools/*/aapt2 dump packagename "$APK" 2>/dev/null | tail -1 || true)"
[ -n "$PKG" ] || PKG="$("$ANDROID_HOME"/build-tools/*/aapt dump badging "$APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
echo "пакет: $PKG"

adb logcat -c
adb install -r -g "$APK"
echo "--- am start ---"
adb shell am start -W -n "$PKG/ru.neurosteno.app.MainActivity"
sleep 6
if [ -n "$AUDIO" ]; then
  adb shell mkdir -p /sdcard/Download
  adb push "$AUDIO" /sdcard/Download/ >/dev/null
  echo "файл положен в /sdcard/Download/$(basename "$AUDIO") (импорт через интерфейс / ACTION_VIEW)"
fi
echo "--- logcat (FATAL / AndroidRuntime / neurosteno) ---"
LOG="$(adb logcat -d -v time 2>/dev/null)"
echo "$LOG" | grep -E "FATAL|AndroidRuntime|Process: $PKG" || echo "FATAL не найдено"
echo "$LOG" | grep -i "neurosteno" | tail -20 || true
echo "$LOG" | grep -q "FATAL EXCEPTION" && { echo "ПАДЕНИЕ приложения" >&2; exit 1; }
echo "смоук пройден: стартовый экран открылся без падения"
