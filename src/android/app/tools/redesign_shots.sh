#!/usr/bin/env bash
# B52: скриншоты 4 экранов (главный, расшифровка с плеером, протокол, настройки) x светлая/тёмная x акцент green/pink.
# Нужны debug-сборка medium на эмуляторе и история с записью cats_c01 (DebugStyleReceiver переключает акцент).
#   src/android/app/tools/redesign_shots.sh [каталог-вывода]
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../../../../scripts/env.sh"
U="$HERE/uia.py"; PKG=ru.neurosteno.app.medium
OUT="${1:-$(cd "$HERE/../../../.." && pwd)/artifacts/demo/screens/purple/redesign}"
t() { python3 "$U" tap "$@" >/dev/null; sleep 1; }
w() { python3 "$U" wait "$@" >/dev/null; }
launch() { adb shell am force-stop $PKG; adb shell am start -n $PKG/ru.neurosteno.app.MainActivity >/dev/null; w id:btn_pick_audio 20; sleep 1; }
for accent in green pink; do
  mkdir -p "$OUT/accent-$accent"
  for theme in light dark; do
    launch
    adb shell am broadcast -a ru.neurosteno.app.DEBUG_ACCENT -p $PKG --es accent $accent >/dev/null
    t desc:"Настройки"; w id:theme_$theme 5
    adb shell input swipe 540 1700 540 900 300; sleep 1
    t id:theme_$theme; sleep 1; adb shell input swipe 540 600 540 1900 300; sleep 1
    python3 "$U" shot "$OUT/accent-$accent/04_settings_$theme.png"
    adb shell input keyevent KEYCODE_BACK; sleep 1
    python3 "$U" shot "$OUT/accent-$accent/01_home_$theme.png"
    t text:cats_c01_v0; w id:btn_export_json 10; sleep 1
    python3 "$U" shot "$OUT/accent-$accent/02a_transcript_top_$theme.png"
    t text:"Добрый день"; sleep 3
    python3 "$U" shot "$OUT/accent-$accent/02_transcript_$theme.png"
    t id:btn_play_pause
    adb shell input swipe 540 600 540 1900 300; adb shell input swipe 540 600 540 1900 300; sleep 1
    t id:btn_protocol; w id:tab_protocol 10; sleep 2
    python3 "$U" shot "$OUT/accent-$accent/03_protocol_$theme.png"
  done
done
