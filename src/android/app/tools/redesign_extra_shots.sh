#!/usr/bin/env bash
# B52: «after»-скриншоты остальных экранов (обработка, ошибка, модели, статистика, запись, листы, диалоги, шрифт x1.6, альбомная).
# Нужна debug-сборка medium на эмуляторе, история с cats_c01 и cats_c04.
#   src/android/app/tools/redesign_extra_shots.sh [каталог-вывода]
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../../../../scripts/env.sh"
U="$HERE/uia.py"; PKG=ru.neurosteno.app.medium; D=/sdcard/Android/data/$PKG/files
S="${1:-$(cd "$HERE/../../../.." && pwd)/artifacts/demo/screens/redesign}"; mkdir -p "$S"
AUD="$ROOT/.cache/data/ru-dialog-tts-cats/audio/cats_c04_v0_call_overlap.wav"
t() { python3 "$U" tap "$@" >/dev/null; sleep 1.2; }; w() { python3 "$U" wait "$@" >/dev/null; }; shot() { python3 "$U" shot "$S/$1.png"; }
back() { adb shell input keyevent KEYCODE_BACK; sleep 1; }
launch() { adb shell am force-stop $PKG; adb shell am start -n $PKG/ru.neurosteno.app.MainActivity >/dev/null; w id:btn_pick_audio 20; sleep 1; }
setTheme() { launch; t desc:"Настройки"; adb shell input swipe 540 1700 540 900 300; sleep 1; t id:theme_$1; back; }

# --- тёмная тема: обработка и ошибка
setTheme dark
adb shell am force-stop $PKG; adb push "$AUD" $D/proc_demo.wav >/dev/null
adb shell am start -W -a android.intent.action.VIEW -d "file://$D/proc_demo.wav" -t "audio/*" -n $PKG/ru.neurosteno.app.MainActivity >/dev/null
sleep 3; shot 10_processing_dark; sleep 9
adb shell "echo notaudio > $D/broken_file.wav"; adb shell am force-stop $PKG
adb shell am start -W -a android.intent.action.VIEW -d "file://$D/broken_file.wav" -t "audio/*" -n $PKG/ru.neurosteno.app.MainActivity >/dev/null
sleep 6; shot 11_error_dark

# --- светлая тема
setTheme light
adb shell am force-stop $PKG; adb shell am start -W -a android.intent.action.VIEW -d "file://$D/proc_demo.wav" -t "audio/*" -n $PKG/ru.neurosteno.app.MainActivity >/dev/null
sleep 3; shot 10_processing_light; t id:btn_cancel_processing; sleep 2
launch; t desc:"Модели"; sleep 1; shot 15_models; back
t desc:"Настройки"; adb shell input swipe 540 1900 540 500 300; sleep 1; adb shell input swipe 540 1900 540 500 300; sleep 1; shot 14b_settings_bottom
t id:btn_open_stats; sleep 1; shot 16_stats; back; back
t id:btn_record; w id:btn_start_record 5; sleep 1; shot 17_record; back
t text:cats_c04_v0; w id:btn_export_json 10; sleep 1
t text:"Другие форматы"; sleep 2; shot 14_export_sheet; back
t desc:"Говорящие"; sleep 1; shot 18_speakers_dialog; back
python3 "$U" longtap text:"Синий цвет мне не нравится"; sleep 1; shot 12_selection
t id:btn_fragment_reprocess; sleep 2; shot 13_fragment_sheet; back; back
t desc:"Действия с репликой"; sleep 1; shot 19_utterance_menu; back

# --- шрифт x1.6
adb shell settings put system font_scale 1.6; launch; shot 20_font16_home
t text:proc_demo; w id:btn_export_json 10; sleep 1; shot 21_font16_transcript; back
t desc:"Настройки"; sleep 1; shot 22_font16_settings; back
t desc:"Модели"; sleep 1; shot 23_font16_models; back
adb shell settings put system font_scale 1.0

# --- альбомная ориентация
adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1; sleep 2
launch; shot 24_landscape_home
t text:proc_demo; w id:btn_export_json 10; sleep 1; shot 25_landscape_transcript
adb shell input swipe 1200 800 1200 300 300; sleep 1; shot 25b_landscape_transcript_scrolled
back; t desc:"Настройки"; sleep 1; shot 26_landscape_settings; back
adb shell am force-stop $PKG; adb shell am start -W -a android.intent.action.VIEW -d "file://$D/proc_demo.wav" -t "audio/*" -n $PKG/ru.neurosteno.app.MainActivity >/dev/null
sleep 3; shot 27_landscape_processing
adb shell settings put system user_rotation 0; adb shell settings put system accelerometer_rotation 1
