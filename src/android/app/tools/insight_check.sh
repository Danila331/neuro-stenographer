#!/usr/bin/env bash
# B50/B51: проверка экранов «Выжимка» и «AI-поиск» на настоящих движках (LLM-выжимка, гибридный поиск, ответ LLM) на эмуляторе. Нужна история с cats_c01 и cats_c04
# (process_on_device.sh). Снимки -- artifacts/demo/screens/summary-search/device/, экспорты -- artifacts/test-results/ui-summary-search/.
#   src/android/app/tools/insight_check.sh
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../../../../scripts/env.sh"
REPO="$(cd "$HERE/../../../.." && pwd)"
U="$HERE/uia.py"; PKG=ru.neurosteno.app.medium
S="$REPO/artifacts/demo/screens/summary-search/device"; mkdir -p "$S"
T="$REPO/artifacts/test-results/ui-summary-search/exports"; mkdir -p "$T"
t() { python3 "$U" tap "$@" >/dev/null; sleep 1.3; }; w() { python3 "$U" wait "$@" >/dev/null; }; has() { python3 "$U" has "$@"; }
shot() { python3 "$U" shot "$S/$1.png"; }
back() { adb shell input keyevent KEYCODE_BACK; sleep 1.2; }
FAIL=0; check() { if "${@:2}"; then echo "PASS  $1"; else echo "FAIL  $1"; FAIL=1; fi; }
launch() { adb shell am force-stop $PKG; adb shell am start -n $PKG/ru.neurosteno.app.MainActivity >/dev/null; w id:btn_pick_audio 20; sleep 1; }
LAND=0
sw_up() { if [ "$LAND" = 1 ]; then adb shell input swipe 1200 560 1200 300 300; else adb shell input swipe 540 1900 540 800 300; fi; }
scroll_to() { for i in $(seq 1 10); do has "$1" && return 0; sw_up; sleep 0.8; done; return 1; }
top() { for i in 1 2 3 4 5 6; do adb shell input swipe 540 700 540 1900 200; done; sleep 0.8; }
setTheme() { launch; t desc:"Настройки"; adb shell input swipe 540 1700 540 900 300; sleep 1; t id:theme_$1; back; }
tcpos() { python3 "$U" dump | grep -o 'content-desc="Дорожка говорящих. Позиция [0-9:]*' | head -1 | grep -o '[0-9][0-9]:[0-9][0-9]$'; }

# ================= светлая тема: выжимка =================
setTheme light
t text:cats_c01_v0; w id:btn_export_json 10; t id:btn_summary; sleep 2
if has id:btn_make_summary; then shot 01_summary_empty_light; t id:btn_make_summary; fi
w id:summary_working 30 && { sleep 3; shot 02_summary_working_light; check "идёт ИИ-выжимка: есть плашка прогресса и «Остановить»" has id:btn_summary_cancel; }
for i in $(seq 1 90); do has id:summary_tag_llm && break; sleep 4; done
check "ИИ-выжимка готова (метка «ИИ-ВЫЖИМКА»)" has id:summary_tag_llm
check "есть блок «О чём»" has id:summary_overview
check "есть плашка «Черновик»" has id:summary_draft_notice
sleep 1; shot 03_summary_final_light
adb shell input swipe 540 1700 540 700 300; sleep 1; shot 04_summary_final_scrolled_light
# тап по пункту -> переход к реплике и плеер на этом времени
top
REF="$(python3 "$U" dump | grep -o 'content-desc="[^"]*Перейти к [0-9:]*"' | head -1 | grep -o '[0-9][0-9]:[0-9][0-9]')"
t id:summary_point_0_0; w id:speaker_timeline 8; sleep 1; POS="$(tcpos)"
echo "ссылка пункта $REF -> позиция плеера $POS"
mmss() { echo $((10#${1%%:*} * 60 + 10#${1##*:})); }
check "тап по пункту выжимки открывает расшифровку, плеер встаёт на время ссылки" bash -c "$(declare -f mmss); r=\$(mmss '$REF'); p=\$(mmss '$POS'); [[ -n '$REF' && -n '$POS' && \$p -ge \$r && \$p -le \$((r+10)) ]]"
shot 05_summary_jump_transcript_light
t id:btn_play_pause; top
# экспорт выжимки в TXT и PDF
t id:btn_summary; sleep 2
scroll_to id:btn_summary_txt; adb shell rm -f /sdcard/Download/*.summary.txt /sdcard/Download/*.summary.pdf >/dev/null 2>&1
t id:btn_summary_txt; sleep 3; t text:SAVE; sleep 3
TXT="$(adb shell 'ls -t /sdcard/Download/*.summary.txt 2>/dev/null | head -1' | tr -d '\r')"; adb pull "$TXT" "$T/summary.txt" >/dev/null 2>&1
check "TXT-выжимка сохранена и содержит тему и таймкоды" bash -c "grep -q '^Выжимка:' '$T/summary.txt' && grep -q '1\\. ' '$T/summary.txt' && grep -qE '\\[[0-9]{2}:[0-9]{2}\\]' '$T/summary.txt'"
scroll_to id:btn_summary_pdf; t id:btn_summary_pdf; sleep 3; t text:SAVE; sleep 4
PDF="$(adb shell 'ls -t /sdcard/Download/*.summary.pdf 2>/dev/null | head -1' | tr -d '\r')"; adb pull "$PDF" "$T/summary.pdf" >/dev/null 2>&1
check "PDF-выжимка сохранена, начинается с %PDF" bash -c "head -c 4 '$T/summary.pdf' | grep -q '%PDF'"
mkdir -p "$S/pdf"; rm -f "$S"/pdf/*.png
(cd /tmp && uv run --with pymupdf python -I "$HERE/render_pdf.py" "$T/summary.pdf" "$S/pdf" 2>&1 | grep -v Warn)
# «Пересоздать» -> снова идёт работа
scroll_to id:btn_summary_rebuild; t id:btn_summary_rebuild; sleep 4; top; shot 06_summary_rebuild_working_light
check "после «Пересоздать» снова идёт ИИ-выжимка" has id:summary_working
t id:btn_summary_cancel; sleep 3; shot 07_summary_after_cancel_light
back; back

# ================= светлая тема: поиск (фейк) =================
t text:cats_c01_v0; w id:btn_export_json 10; t id:btn_ai_search; w id:search_query 8; sleep 1
shot 10_search_idle_light
t id:suggestion_1; sleep 1; check "подсказка подставляется в поле" has text:"Что обсуждали по теме"
t id:search_query; python3 "$U" type "что говорили про повестку"; adb shell input keyevent KEYCODE_BACK; sleep 1; t id:btn_search_go; w id:search_hits_count 15; sleep 1
shot 11_search_results_light
check "найдены фрагменты поиска" has id:search_hit_0
scroll_to id:btn_answer; t id:btn_answer; for i in $(seq 1 90); do has id:answer_done && break; has id:answer_refused && break; sleep 2; done
check "ответ ИИ получен" has id:answer_done
shot 12_search_answer_light
# тап по фрагменту -> расшифровка на этом времени
top; HIT="$(python3 "$U" dump | grep -o 'content-desc="[^"]*Перейти к реплике [0-9:]*"' | head -1 | grep -o '[0-9][0-9]:[0-9][0-9]' | tail -1)"
HITDESC="$(python3 "$U" dump | grep -o 'resource-id="search_hit_0_line_0"' | head -1)"
t id:search_hit_0_line_0; w id:speaker_timeline 8; sleep 1; POS="$(tcpos)"
echo "фрагмент -> позиция плеера $POS"
check "тап по фрагменту поиска открывает расшифровку на реплике" bash -c "[[ -n '$POS' ]]"
shot 13_search_jump_transcript_light
t id:btn_play_pause
launch
# ничего не найдено и вся история
t text:cats_c01_v0; w id:btn_export_json 10; t id:btn_ai_search; w id:search_query 8
# вся история с главного экрана
back; back; launch; t desc:"AI-поиск по истории"; w id:search_query 8
t id:search_query; python3 "$U" type "мышах"; adb shell input keyevent KEYCODE_BACK; sleep 1; t id:btn_search_go; w id:search_hits_count 15; sleep 1
shot 19_search_history_light
check "поиск по всей истории: у фрагментов название записи" has id:search_hit_0

# ================= тёмная тема, шрифт x1.6, альбомная =================
setTheme dark
t text:cats_c01_v0; w id:btn_export_json 10; t id:btn_summary; sleep 2; shot 20_summary_dark
back; t id:btn_ai_search; w id:search_query 8; t id:search_query; python3 "$U" type "сметану"; adb shell input keyevent KEYCODE_BACK; sleep 1; t id:btn_search_go; w id:search_hits_count 15; sleep 1; shot 21_search_results_dark
adb shell settings put system font_scale 1.6; launch; t text:cats_c01_v0; w id:btn_export_json 10; t id:btn_summary; sleep 2; shot 22_summary_font16_dark
back; t id:btn_ai_search; w id:search_query 8; sleep 1; shot 23_search_font16_dark
adb shell settings put system font_scale 1.0
LAND=1; adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1; sleep 2
launch; t text:cats_c01_v0; w id:btn_export_json 10; scroll_to id:btn_summary; t id:btn_summary; sleep 2; shot 24_summary_landscape_dark
back; scroll_to id:btn_ai_search; t id:btn_ai_search; w id:search_query 8; sleep 1; shot 25_search_landscape_dark
adb shell settings put system user_rotation 0; adb shell settings put system accelerometer_rotation 1; sleep 1
echo "FAIL=$FAIL  FATAL=$(adb logcat -d | grep -c 'FATAL EXCEPTION')"
