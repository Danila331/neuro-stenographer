#!/usr/bin/env bash
# Запуск эмулятора БЕЗ окна и ждём sys.boot_completed. Одновременно -- не больше одного эмулятора.
#
#   scripts/emulator.sh jury-4g            # запустить AVD и дождаться загрузки
#   scripts/emulator.sh jury-4g --wipe     # со сбросом данных
#   scripts/emulator.sh stop               # выключить (adb emu kill)
#
# Флаги: -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect (без GPU-ускорения хоста, как у проверки жюри).
# Лог: $ROOT/.local/emulator-<avd>.log.  BOOT_TIMEOUT=<сек> (по умолчанию 420).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "$HERE/env.sh"

ARG="${1:-}"
[ -n "$ARG" ] || { echo "usage: $0 <avd-name> [--wipe] | stop" >&2; exit 2; }

if [ "$ARG" = "stop" ]; then
  adb emu kill 2>/dev/null || true
  pkill -f "qemu-system-aarch64.*-avd" 2>/dev/null || true
  echo "эмулятор остановлен"
  exit 0
fi

AVD="$ARG"
WIPE=""
[ "${2:-}" = "--wipe" ] && WIPE="-wipe-data"
TIMEOUT="${BOOT_TIMEOUT:-420}"

command -v emulator >/dev/null || { echo "нет emulator в PATH (sdkmanager emulator)"; exit 1; }
[ -d "$ANDROID_AVD_HOME/$AVD.avd" ] || { echo "AVD '$AVD' не найден в $ANDROID_AVD_HOME (создайте: src/android/scripts/create_avds.sh)"; exit 1; }

if adb devices 2>/dev/null | grep -q '^emulator-'; then
  echo "уже запущен эмулятор -- одновременно допускается только один. Выключите: $0 stop" >&2
  exit 1
fi

LOG="$ROOT/.local/emulator-$AVD.log"
echo "запуск $AVD (лог $LOG)"
nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -no-metrics -no-snapshot \
  -gpu swiftshader_indirect $WIPE >"$LOG" 2>&1 &
echo $! > "$ROOT/.local/emulator.pid"

adb wait-for-device
start=$(date +%s)
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  if [ $(( $(date +%s) - start )) -gt "$TIMEOUT" ]; then
    echo "таймаут загрузки ($TIMEOUT с); см. $LOG" >&2
    exit 1
  fi
  sleep 3
done
# дать системе дозагрузиться (экран разблокировки/пакетный менеджер)
adb shell input keyevent 82 >/dev/null 2>&1 || true
echo "boot completed за $(( $(date +%s) - start )) с: $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
