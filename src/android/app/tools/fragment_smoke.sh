#!/usr/bin/env bash
# Смоук «Обработать заново» на запущенном эмуляторе (medium debug, пустая история): cats_c04 -> имена говорящих -> пересчёт одной реплики
# -> пересчёт двух реплик с диаризацией (числом 2) -> «Отменить» -> снова пересчёт; на каждом шаге results/*.json проходит stenoeval validate.
#   src/android/app/tools/fragment_smoke.sh <apk> [out-dir]
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../../../../scripts/env.sh"
REPO="$(cd "$HERE/../../../.." && pwd)"
APK="${1:?usage: $0 <apk> [out-dir]}"; OUT="${2:-$REPO/artifacts/test-results/ui-fragment}"; S="$REPO/artifacts/demo/screens"
U="$HERE/uia.py"; PKG=ru.neurosteno.app.medium; D=/sdcard/Android/data/$PKG/files/results; B=cats_c04_v0_call_overlap
AUD="$ROOT/.cache/data/ru-dialog-tts-cats/audio/$B.wav"
mkdir -p "$OUT/base" "$OUT/a_single" "$OUT/b_two" "$OUT/b_undo" "$OUT/c_two_again" "$S"
t() { python3 "$U" tap "$@"; }; w() { python3 "$U" wait "$@"; }; shot() { python3 "$U" shot "$S/$1.png"; }
FAIL=0
pull() { for f in "$B.json" "$B.full.json" "$B.done.json"; do adb pull "$D/$f" "$1/$f" >/dev/null; done; }
validate() { (cd "$REPO/src/eval/stenoeval" && uv run stenoeval validate "$1" 2>&1 | tail -1); }
names() { ID=$(adb shell "run-as $PKG ls files/history" | tr -d '\r' | head -1); adb shell "run-as $PKG cat files/history/$ID/transcript.json" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['speakerNames'])"; }
# same A B -- тексты и говорящие совпадают; differ A B -- есть отличия
cmpjson() { python3 -c "
import json,sys
f=lambda p:[(u['speaker_id'],u['start_ms'],u['text']) for u in json.load(open(p))['transcript']]
a,b=f(sys.argv[2]),f(sys.argv[3])
sys.exit(0 if (a==b)==(sys.argv[1]=='same') else 1)" "$@"; }
same() { cmpjson same "$@"; }; differ() { cmpjson differ "$@"; }
check() { if "${@:2}"; then echo "PASS  $1"; else echo "FAIL  $1"; FAIL=1; fi; }

adb uninstall $PKG >/dev/null 2>&1; adb install -r "$APK" | tail -1
SKIP_INSTALL=1 "$HERE/../../scripts/process_on_device.sh" "$APK" "$AUD" "$OUT/base" 600 2>&1 | grep -E "status="
validate "$OUT/base/$B.json"
adb shell am force-stop $PKG; adb shell am start -n $PKG/ru.neurosteno.app.MainActivity >/dev/null; w id:btn_pick_audio 20; sleep 1
t text:cats_c04; w id:btn_export_json 10; sleep 1
t desc:"Говорящие"; sleep 1; t id:speaker_name_speaker_1; python3 "$U" type "Мурзик"; t id:speaker_name_speaker_2; python3 "$U" type "Барсик"; sleep 1; t text:Готово; sleep 2
echo "имена до: $(names)"; shot 40_fragment_before

# A. одна реплика, без пересчёта говорящих
python3 "$U" longtap text:"Синий цвет мне не нравится"; sleep 1; shot 41_fragment_selection
t id:btn_fragment_reprocess; sleep 2; shot 42_fragment_options
t id:fragment_recompute; sleep 1; shot 42b_fragment_options_recompute; t id:fragment_recompute; sleep 1
t id:btn_fragment_start; sleep 1; shot 43_fragment_progress
w text:"обработан заново" 60; sleep 1; shot 44_fragment_result
pull "$OUT/a_single"; validate "$OUT/a_single/$B.json"
check "A: имена сохранились" bash -c "[[ \"$(names)\" == *Мурзик* && \"$(names)\" == *Барсик* ]]"
check "A: реплика заменена" differ "$OUT/base/$B.json" "$OUT/a_single/$B.json"
check "A: маркер с блоком fragment" bash -c "python3 -c \"import json;m=json.load(open('$OUT/a_single/$B.done.json'));assert m['fragment']['outcome']=='REPLACED'\""
sleep 8   # дать snackbar исчезнуть

# B. две реплики, пересчёт говорящих, число 2
adb shell input swipe 540 1500 540 500 400; sleep 1
python3 "$U" longtap text:"С рыбками? Ну"; sleep 1; t text:"голосуем"; sleep 1; shot 45_fragment_selection_two
t id:btn_fragment_reprocess; sleep 2; t id:fragment_recompute; sleep 1; t id:fragment_speakers_2; sleep 1; shot 46_fragment_options_speakers
t id:btn_fragment_start; sleep 1; shot 47_fragment_progress_recompute
w text:"обработан заново" 90; sleep 1; shot 48_fragment_result_snackbar
pull "$OUT/b_two"; validate "$OUT/b_two/$B.json"
check "B: имена сохранились" bash -c "[[ \"$(names)\" == *Мурзик* && \"$(names)\" == *Барсик* ]]"
check "B: реплики заменены" differ "$OUT/a_single/$B.json" "$OUT/b_two/$B.json"

# «Отменить»
t text:"Отменить"; w text:"Возвращено как было" 10; sleep 1; shot 49_fragment_undone
pull "$OUT/b_undo"; validate "$OUT/b_undo/$B.json"
check "Отмена: results совпадают с состоянием до пересчёта" same "$OUT/a_single/$B.json" "$OUT/b_undo/$B.json"
check "Отмена: имена на месте" bash -c "[[ \"$(names)\" == *Мурзик* && \"$(names)\" == *Барсик* ]]"
echo FATAL=$(adb logcat -d | grep -c "FATAL EXCEPTION"); exit $FAIL
