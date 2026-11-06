#!/usr/bin/env bash
# Настоящая обработка одного аудиофайла на запущенном эмуляторе/устройстве (DEBUG-сборка, нужен run-as):
# установка APK, копия файла в приватную папку приложения, ACTION_VIEW (обработка стартует сама), ожидание результата,
# выгрузка JSON в <out-dir>, замер пикового PSS/RSS процесса (dumpsys meminfo, опрос раз в 2 с) и времени.
#   src/android/scripts/process_on_device.sh <debug.apk> <audio.wav> [out-dir] [timeout-sec]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../../scripts/env.sh
source "$HERE/../../../scripts/env.sh"

APK="${1:?usage: $0 <debug.apk> <audio> [out-dir] [timeout]}"
AUDIO="${2:?}"
OUT="${3:-$NEUROSTENO_ROOT/artifacts/test-results/device}"
TIMEOUT="${4:-900}"
mkdir -p "$OUT"
PKG="$("$ANDROID_HOME"/build-tools/*/aapt2 dump packagename "$APK" | tail -1)"
NAME="$(basename "$AUDIO")"
BASE="${NAME%.*}"

adb install -r -g "$APK" >/dev/null
adb shell am force-stop "$PKG"
adb push "$AUDIO" "/data/local/tmp/$NAME" >/dev/null
adb shell run-as "$PKG" mkdir -p files
adb shell run-as "$PKG" cp "/data/local/tmp/$NAME" "files/$NAME"
adb shell run-as "$PKG" rm -rf "files/../cache/none" >/dev/null 2>&1 || true
RES="/sdcard/Android/data/$PKG/files/results"
adb shell rm -f "$RES/$BASE.json" "$RES/$BASE.full.json" >/dev/null 2>&1 || true
adb logcat -c

START=$(date +%s)
adb shell am start -W -a android.intent.action.VIEW -d "file:///data/data/$PKG/files/$NAME" -t "audio/*" -n "$PKG/ru.neurosteno.app.MainActivity" >/dev/null
PEAK_PSS=0; PEAK_RSS=0; STATUS=timeout
while [ $(( $(date +%s) - START )) -lt "$TIMEOUT" ]; do
  PID="$(adb shell pidof "$PKG" | tr -d '\r' || true)"
  if [ -n "$PID" ]; then
    LINE="$(adb shell dumpsys meminfo "$PID" 2>/dev/null | grep -m1 "TOTAL PSS" || true)"
    PSS="$(echo "$LINE" | sed -n 's/.*TOTAL PSS: *\([0-9]*\).*/\1/p')"
    RSS="$(echo "$LINE" | sed -n 's/.*TOTAL RSS: *\([0-9]*\).*/\1/p')"
    [ -n "$PSS" ] && [ "$PSS" -gt "$PEAK_PSS" ] && PEAK_PSS=$PSS
    [ -n "$RSS" ] && [ "$RSS" -gt "$PEAK_RSS" ] && PEAK_RSS=$RSS
  fi
  LOG="$(adb logcat -d -s Neurosteno:I AndroidRuntime:E 2>/dev/null)"
  if echo "$LOG" | grep -q "done $NAME"; then STATUS=done; break; fi
  if echo "$LOG" | grep -qE "processing failed|pipeline failed|FATAL EXCEPTION"; then STATUS=failed; break; fi
  sleep 2
done
WALL=$(( $(date +%s) - START ))
echo "status=$STATUS wall=${WALL}s peak_PSS=$((PEAK_PSS/1024))MB peak_RSS=$((PEAK_RSS/1024))MB"
adb logcat -d -s Neurosteno:I AndroidRuntime:E | tail -8
if [ "$STATUS" = done ]; then
  adb pull "$RES/$BASE.json" "$OUT/$BASE.json" >/dev/null
  adb pull "$RES/$BASE.full.json" "$OUT/$BASE.full.json" >/dev/null || true
  echo "результат: $OUT/$BASE.json"
fi
[ "$STATUS" = done ]
