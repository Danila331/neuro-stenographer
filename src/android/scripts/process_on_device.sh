#!/usr/bin/env bash
# Настоящая обработка одного аудиофайла на запущенном эмуляторе/устройстве (debug или release APK):
# установка APK, копия файла в приватную папку приложения, ACTION_VIEW (обработка стартует сама), ожидание результата,
# выгрузка JSON в <out-dir>, замер пикового PSS/RSS процесса (dumpsys meminfo, опрос раз в 2 с) и времени.
# Завершение определяется по ФАЙЛУ-МАРКЕРУ <имя>.done.json (пишется приложением последним, когда результаты уже на диске) или
# <имя>.error.json (сбой); строка logcat `done …` -- запасной признак (на части устройств, напр. Pixel 10, она ненадёжна, B32).
#   [SKIP_INSTALL=1] src/android/scripts/process_on_device.sh <apk> <audio.wav> [out-dir] [timeout-sec]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../../scripts/env.sh
source "$HERE/../../../scripts/env.sh"

APK="${1:?usage: $0 <debug.apk> <audio> [out-dir] [timeout]}"
AUDIO="${2:?}"
OUT="${3:-$NEUROSTENO_ROOT/artifacts/test-results/device}"
TIMEOUT="${4:-900}"
mkdir -p "$OUT"
AAPT2="$(printf '%s\n' "$ANDROID_HOME"/build-tools/*/aapt2 | sort | tail -1)"
PKG="$("$AAPT2" dump badging "$APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
NAME="$(basename "$AUDIO")"
BASE="${NAME%.*}"

# SKIP_INSTALL=1 -- не переустанавливать (APK medium ~1.6 ГБ, а на эмуляторе с 12 ГБ /data замена установленного не помещается)
[ "${SKIP_INSTALL:-0}" = 1 ] || adb install -r -g "$APK" >/dev/null
adb shell am force-stop "$PKG"
# файл кладётся в app-specific внешнюю папку приложения (читается без разрешений; работает и для release-APK)
IN_DIR="/sdcard/Android/data/$PKG/files"
adb shell am start -n "$PKG/ru.neurosteno.app.MainActivity" >/dev/null   # приложение создаёт папку само (владелец -- uid приложения)
sleep 4
adb shell am force-stop "$PKG"
adb push "$AUDIO" "$IN_DIR/$NAME" >/dev/null
RES="$IN_DIR/results"   # если эта папка недоступна приложению (остаток прошлой установки с другим uid), оно пишет в results-<uid>
for D in $(adb shell "ls -d $IN_DIR/results* 2>/dev/null" | tr -d '\r'); do
  adb shell rm -f "$D/$BASE.json" "$D/$BASE.full.json" "$D/$BASE.done.json" "$D/$BASE.error.json" >/dev/null 2>&1 || true
done
adb logcat -c

START=$(date +%s)
adb shell am start -W -a android.intent.action.VIEW -d "file://$IN_DIR/$NAME" -t "audio/*" -n "$PKG/ru.neurosteno.app.MainActivity" >/dev/null
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
  # основной признак -- файл-маркер рядом с результатом
  for D in $(adb shell "ls -d $IN_DIR/results* 2>/dev/null" | tr -d '\r'); do
    if [ "$(adb shell "[ -f '$D/$BASE.done.json' ] && echo 1" | tr -d '\r')" = 1 ]; then STATUS=done; RES="$D"; break 2; fi
    if [ "$(adb shell "[ -f '$D/$BASE.error.json' ] && echo 1" | tr -d '\r')" = 1 ]; then STATUS=failed; RES="$D"; break 2; fi
  done
  # запасной: logcat
  LOG="$(adb logcat -d -s Neurosteno:I AndroidRuntime:E 2>/dev/null)"
  if echo "$LOG" | grep -q "done $NAME"; then
    sleep 1; STATUS=done
    LOGDIR="$(echo "$LOG" | grep "done $NAME" | tail -1 | grep -o ' results=[^ ]*' | sed 's/^ results=//')"
    [ -n "$LOGDIR" ] && RES="$LOGDIR"
    break
  fi
  if echo "$LOG" | grep -qE "processing failed|pipeline failed|failed $NAME|FATAL EXCEPTION"; then STATUS=failed; break; fi
  sleep 2
done
WALL=$(( $(date +%s) - START ))
echo "status=$STATUS wall=${WALL}s peak_PSS=$((PEAK_PSS/1024))MB peak_RSS=$((PEAK_RSS/1024))MB"
adb logcat -d -s Neurosteno:I AndroidRuntime:E | tail -8
if [ "$STATUS" = done ]; then
  adb pull "$RES/$BASE.json" "$OUT/$BASE.json" >/dev/null
  adb pull "$RES/$BASE.full.json" "$OUT/$BASE.full.json" >/dev/null || true
  adb pull "$RES/$BASE.done.json" "$OUT/$BASE.done.json" >/dev/null || echo "ВНИМАНИЕ: маркера $BASE.done.json нет (старая сборка?)"
  echo "результат: $OUT/$BASE.json"
  [ -f "$OUT/$BASE.done.json" ] && python3 - "$OUT/$BASE.done.json" <<'PY'
import json, sys
m = json.load(open(sys.argv[1], encoding="utf-8"))
print("маркер: rtf=%s processing_ms=%s peak_pss_mb(app)=%s threads=%s device=%s sdk=%s реплик=%s" % (
    m.get("rtf"), m.get("processing_ms"), m.get("peak_pss_mb"), m.get("threads"), m.get("device_model"), m.get("android_sdk"), m.get("utterances")))
PY
elif [ "$STATUS" = failed ]; then
  adb pull "$RES/$BASE.error.json" "$OUT/$BASE.error.json" >/dev/null 2>&1 && cat "$OUT/$BASE.error.json"
fi
[ "$STATUS" = done ]
