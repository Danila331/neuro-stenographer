#!/usr/bin/env bash
# B13: проверка живой расшифровки на эмуляторе: WAV подаётся в «Запись» как микрофон (DebugMicReceiver, только debug), в реальном времени.
# Снимает экран записи в ходе речи, собирает строку лога `live:` (RTF превью, загрузка, выгрузка), PSS до/после остановки,
# ждёт итоговую обработку и проверяет, что результат появился. Нужна debug-сборка medium с потоковой моделью (stage_models.sh --map).
#   src/android/app/tools/live_check.sh <audio.wav> <метка> [секунд записи] [каталог скриншотов]
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../../../../scripts/env.sh"
WAV="${1:?usage: $0 <audio.wav> <label> [seconds] [out-dir]}"; LABEL="${2:?}"; SECS="${3:-30}"
OUT="${4:-$(cd "$HERE/../../../.." && pwd)/artifacts/demo/screens/purple/live}"; mkdir -p "$OUT"
U="$HERE/uia.py"; PKG=ru.neurosteno.app.medium; D=/sdcard/Android/data/$PKG/files
pss() { adb shell dumpsys meminfo "$(adb shell pidof $PKG | tr -d '\r')" 2>/dev/null | sed -n 's/.*TOTAL PSS: *\([0-9]*\).*/\1/p' | head -1; }
adb shell am force-stop $PKG; adb shell am start -n $PKG/ru.neurosteno.app.MainActivity >/dev/null; python3 "$U" wait id:btn_pick_audio 20; sleep 1
adb push "$WAV" $D/mic_live.wav >/dev/null
adb shell am broadcast -a ru.neurosteno.app.DEBUG_MIC_WAV -p $PKG --es path $D/mic_live.wav >/dev/null
adb logcat -c
python3 "$U" tap id:btn_record >/dev/null; sleep 1; python3 "$U" tap id:btn_start_record >/dev/null
sleep 3; python3 "$U" shot "$OUT/${LABEL}_1_start.png"
sleep $(( SECS / 2 )); python3 "$U" shot "$OUT/${LABEL}_2_middle.png"
sleep $(( SECS - SECS / 2 - 3 )); python3 "$U" shot "$OUT/${LABEL}_3_end.png"
echo "PSS во время записи с превью: $(pss) КБ"
python3 "$U" tap id:btn_stop_record >/dev/null
sleep 1; python3 "$U" shot "$OUT/${LABEL}_4_processing.png"
python3 "$U" wait id:btn_export_json 120 >/dev/null && echo "итог: расшифровка открыта" || echo "итог: НЕ дождались"
sleep 1; python3 "$U" shot "$OUT/${LABEL}_5_result.png"
echo "PSS после обработки: $(pss) КБ"
adb logcat -d | grep -E "Neurosteno: live:" | sed 's/^.*Neurosteno: //'
echo "FATAL=$(adb logcat -d | grep -c 'FATAL EXCEPTION')"
adb shell am broadcast -a ru.neurosteno.app.DEBUG_MIC_WAV -p $PKG >/dev/null
