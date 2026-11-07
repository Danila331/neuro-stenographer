#!/usr/bin/env bash
# B54: проверка дорожки говорящих и экспорта в PDF на эмуляторе (нужна история с cats_c01 и cats_c04: process_on_device.sh).
# Снимает дорожку (светлая/тёмная, перемотка тапом и протяжкой, шрифт x1.6, альбомная, подсветка добавленных отрезков после
# «Обработать заново»), экспортирует PDF через системный диалог (SAF), забирает файл, проверяет заголовок %PDF и рендерит страницы
# на хосте (PyMuPDF через uv). Вывод: артефакты в artifacts/demo/screens/timeline-pdf/.
#   src/android/app/tools/timeline_pdf_check.sh [каталог-вывода]
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../../../../scripts/env.sh"
REPO="$(cd "$HERE/../../../.." && pwd)"
U="$HERE/uia.py"; PKG=ru.neurosteno.app.medium
S="${1:-$REPO/artifacts/demo/screens/timeline-pdf}"; mkdir -p "$S/pdf"
PDF_REC="${PDF_REC:-Совещание_длинное}"   # запись для экспорта PDF (по умолчанию длинная: несколько страниц); PDF_REC=cats_c01_v0 -- короткая
T="$REPO/artifacts/test-results/ux-timeline-pdf/pdf"; mkdir -p "$T"
t() { python3 "$U" tap "$@" >/dev/null; sleep 1.3; }; w() { python3 "$U" wait "$@" >/dev/null; }; shot() { python3 "$U" shot "$S/$1.png"; }
desc() { python3 "$U" dump | grep -o 'content-desc="Дорожка[^"]*"' | head -1; }
launch() { adb shell am force-stop $PKG; adb shell am start -n $PKG/ru.neurosteno.app.MainActivity >/dev/null; w id:btn_pick_audio 20; sleep 1; }
back() { adb shell input keyevent KEYCODE_BACK; sleep 1; }
top() { for i in 1 2 3 4 5; do adb shell input swipe 540 700 540 1700 200; done; sleep 1; }
setTheme() { launch; t desc:"Настройки"; adb shell input swipe 540 1700 540 900 300; sleep 1; t id:theme_$1; back; }
FAIL=0; check() { if "${@:2}"; then echo "PASS  $1"; else echo "FAIL  $1"; FAIL=1; fi; }

# --- перемотка тапом и протяжкой (позиция в описании дорожки)
setTheme light
t text:cats_c01_v0; w id:btn_export_json 10; sleep 1
shot 01_timeline_light
read X0 Y X1 <<< "$(python3 "$U" dump | python3 -c "
import re,sys
m=re.search(r'resource-id=\"speaker_timeline\".*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',sys.stdin.read())
x0,y0,x1,y1=map(int,m.groups()); print(x0,(y0+y1)//2,x1)")"
MID=$(( (X0 + X1) / 2 ))
adb shell input tap $MID $Y; sleep 1.2; D=$(desc); echo "$D"
check "тап по середине -> позиция около 00:29" bash -c "[[ '$D' == *'00:28 из'* || '$D' == *'00:29 из'* || '$D' == *'00:30 из'* ]]"
shot 02_timeline_tap_light
adb shell input swipe $((X0+60)) $Y $((X0 + (X1-X0)*8/10)) $Y 500; sleep 1.2; D=$(desc); echo "$D"
check "протяжка вправо -> позиция около 00:46" bash -c "[[ '$D' == *'00:45 из'* || '$D' == *'00:46 из'* || '$D' == *'00:47 из'* ]]"
t id:btn_play_pause; sleep 2; shot 03_timeline_playing_light; t id:btn_play_pause

# --- экспорт PDF через SAF (запись $PDF_REC; если рядом составлен протокол -- он попадёт в PDF)
back; sleep 1; t text:"$PDF_REC"; w id:btn_export_json 10; sleep 1
top; t text:"Другие форматы"; sleep 1.5; shot 04_export_sheet_pdf
adb shell rm -f /sdcard/Download/*.pdf >/dev/null 2>&1
t text:"PDF (расшифровка"; sleep 3
t text:SAVE; sleep 4
PDF="$(adb shell 'ls -t /sdcard/Download/*.pdf 2>/dev/null | head -1' | tr -d '\r')"
adb pull "$PDF" "$T/$(basename "$PDF")" >/dev/null 2>&1
check "PDF сохранён и начинается с %PDF" bash -c "head -c 4 '$T/$(basename "$PDF")' | grep -q '%PDF'"
rm -f "$S"/pdf/page*.png
(cd /tmp && uv run --with pymupdf python -I "$HERE/render_pdf.py" "$T/$(basename "$PDF")" "$S/pdf" 2>&1 | grep -v Warn)
back; t text:cats_c01_v0 >/dev/null 2>&1

# --- тёмная тема, шрифт x1.6, альбомная
setTheme dark
t text:cats_c01_v0; w id:btn_export_json 10; sleep 1; t id:btn_play_pause; sleep 2; t id:btn_play_pause; shot 05_timeline_dark
adb shell settings put system font_scale 1.6; launch; t text:proc_demo >/dev/null 2>&1; t text:cats_c01_v0; w id:btn_export_json 10; sleep 1; shot 06_timeline_font16_dark
adb shell settings put system font_scale 1.0
adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1; sleep 2
launch; t text:cats_c01_v0; w id:btn_export_json 10; sleep 1; shot 07_timeline_landscape_dark
adb shell settings put system user_rotation 0; adb shell settings put system accelerometer_rotation 1; sleep 1

# --- подсветка добавленных отрезков после «Обработать заново»
setTheme light
t text:cats_c04_v0; w id:btn_export_json 10; sleep 1
python3 "$U" longtap text:"Синий цвет мне не нравится"; sleep 1
t id:btn_fragment_reprocess; sleep 2; t id:btn_fragment_start; sleep 1
w text:"обработан заново" 90; sleep 1.5; shot 08_timeline_added_highlight
echo "FAIL=$FAIL  FATAL=$(adb logcat -d | grep -c 'FATAL EXCEPTION')"
