#!/usr/bin/env bash
# Offline demo capture. UI labels/SAF layout depend on the device build; see README.
set -euo pipefail
usage() { echo "Usage: $0 --serial SERIAL --file NAME [--package PACKAGE] --out NEW_DIR [--dry-run]"; }
serial=''; package=ru.neurosteno.app.medium; audio=''; out=''; dry=0
while [ "$#" -gt 0 ]; do
 case "$1" in
  --serial|--file|--package|--out) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; key=$1; value=$2; shift 2; case "$key" in --serial) serial=$value;; --file) audio=$value;; --package) package=$value;; --out) out=$value;; esac;;
  --dry-run) dry=1; shift;; -h|--help) usage; exit 0;; *) echo "Unknown argument: $1" >&2; usage >&2; exit 2;;
 esac
done
[ -n "$serial" ] && [ -n "$audio" ] && [ -n "$out" ] || { usage >&2; exit 2; }
[[ "$package" =~ ^[a-zA-Z0-9_.]+$ ]] || { echo 'Invalid package' >&2; exit 2; }
audio=${audio##*/}; [[ "$audio" =~ ^[a-zA-Z0-9._-]+$ ]] || { echo 'Filename must contain only letters, digits, dot, underscore or hyphen' >&2; exit 2; }
base=${audio%.*}; [ -n "$base" ] || exit 2
here=$(cd "$(dirname "$0")" && pwd); root=$(cd "$here/../.." && pwd); uia="$root/src/android/app/tools/uia.py"
remote="/sdcard/Download/NeuroSteno-demo/$audio"; results="/sdcard/Android/data/$package/files/results"; marker="$results/$base.done.json"
if [ "$dry" = 1 ]; then
 cat <<PLAN
DRY-RUN: устройство не используется
serial=$serial package=$package
аудио=$remote; результат=$marker; локальная папка=$out
1. Проверить файл и доступность UI; включить «Не беспокоить» и режим полёта (при отказе — вручную).
2. Записать экран частями по 170 секунд; импорт через SAF из Download/NeuroSteno-demo.
3. Дождаться нового .done.json или .error.json; открыть расшифровку, прокрутить и нажать реплику.
4. Переименовать speaker_1, выбрать реплику долгим нажатием, выполнить «Обработать заново».
5. Экспорт JSON через системный диалог; открыть «Протокол заседания» и запустить генерацию.
6. Остановить запись, adb pull частей; при наличии ffmpeg склеить. Устройство и UI требуют ручной проверки.
PLAN
 exit 0
fi
command -v adb >/dev/null || { echo 'adb не найден' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'python3 не найден' >&2; exit 1; }
[ ! -e "$out" ] || { echo "Каталог уже существует: $out" >&2; exit 1; }
mkdir -p "$out"; out=$(cd "$out" && pwd)
export ANDROID_SERIAL="$serial"
adb_cmd() { adb -s "$serial" "$@"; }
tap() { python3 "$uia" tap "$1"; }
wait_ui() { python3 "$uia" wait "$1" "${2:-30}"; }
adb_cmd get-state >/dev/null
adb_cmd shell test -f "$remote" || { echo "Нет файла $remote" >&2; exit 1; }
# Unique paths; do not remove or overwrite any pre-existing user recordings.
runid="neuro-demo-$(date +%Y%m%d-%H%M%S)-$$"
part=0; record_pid=''; supervisor=''; recorded=()
stop_current() {
 if [ -n "$record_pid" ]; then
  # The pid belongs to our remote shell, and the command line must include our unique path.
  adb_cmd shell "p=\$(cat /sdcard/${runid}.pid 2>/dev/null); case \$(tr '\\0' ' ' </proc/\$p/cmdline 2>/dev/null) in *${runid}*) kill -2 \$p;; esac" >/dev/null 2>&1 || true
  wait "$record_pid" 2>/dev/null || true; record_pid=''
 fi
}
cleanup() { stop_current; }
trap cleanup EXIT INT TERM
start_part() {
 part=$((part+1)); current="/sdcard/${runid}-$(printf '%03d' "$part").mp4"; recorded+=("$current")
 adb_cmd shell "screenrecord --time-limit 170 '$current' & echo \$! > '/sdcard/${runid}.pid'; wait" >/dev/null 2>&1 & record_pid=$!
}
rotate_if_needed() { if [ -n "$record_pid" ] && ! kill -0 "$record_pid" 2>/dev/null; then wait "$record_pid" || true; record_pid=''; start_part; fi; }
# Run a long wait in small increments, rotating even when processing exceeds 3 minutes.
pause() { local end=$((SECONDS+$1)); while [ "$SECONDS" -lt "$end" ]; do rotate_if_needed; sleep 1; done; }
adb_cmd shell cmd notification set_dnd on >/dev/null 2>&1 || echo 'Не удалось включить «Не беспокоить»: включите вручную.' >&2
adb_cmd shell cmd connectivity airplane-mode enable >/dev/null 2>&1 || echo 'Не удалось включить режим полёта: включите вручную.' >&2
old_done=$(adb_cmd shell cat "$marker" 2>/dev/null || true); old_error=$(adb_cmd shell cat "$results/$base.error.json" 2>/dev/null || true)
start_part
adb_cmd shell am start -n "$package/ru.neurosteno.app.MainActivity" >/dev/null
wait_ui id:btn_pick_audio 30; pause 3; tap id:btn_pick_audio; pause 2
# The Android document picker normally opens the last location; fail with a useful instruction if its layout differs.
if ! python3 "$uia" has "text:$audio"; then
 if python3 "$uia" has 'desc:Show roots'; then tap 'desc:Show roots'; pause 1; fi
 if python3 "$uia" has 'text:Downloads'; then tap 'text:Downloads'; pause 1
 elif python3 "$uia" has 'text:Загрузки'; then tap 'text:Загрузки'; pause 1; fi
 if python3 "$uia" has 'text:NeuroSteno-demo'; then tap 'text:NeuroSteno-demo'; pause 1; fi
fi
wait_ui "text:$audio" 15 || { echo 'Откройте папку Download/NeuroSteno-demo в системном выборе и повторите запись.' >&2; exit 1; }
tap "text:$audio"
end=$((SECONDS+900)); done_json=''
while [ "$SECONDS" -lt "$end" ]; do
 rotate_if_needed
 done_json=$(adb_cmd shell cat "$marker" 2>/dev/null || true)
 error_json=$(adb_cmd shell cat "$results/$base.error.json" 2>/dev/null || true)
 if [ -n "$error_json" ] && [ "$error_json" != "$old_error" ]; then echo "Ошибка обработки: $error_json" >&2; exit 1; fi
 if [ -n "$done_json" ] && [ "$done_json" != "$old_done" ]; then
  printf '%s' "$done_json" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d.get("status")=="done" and not d.get("problems"),d' || { echo 'Маркер содержит ошибки сохранения' >&2; exit 1; }; break
 fi
 pause 2
done
[ -n "$done_json" ] && [ "$done_json" != "$old_done" ] || { echo 'Таймаут ожидания нового маркера' >&2; exit 1; }
wait_ui id:btn_export_json 60; pause 3
# Select the first transcript card by its accessibility description, not screen coordinates.
card() { python3 "$uia" dump | python3 -c 'import sys,xml.etree.ElementTree as E; r=E.parse(sys.stdin).getroot(); a=[n.get("content-desc","") for n in r.iter("node")]; a=[x for x in a if ", " in x and ". " in x and not x.startswith("Действия с репликой")]; print(a[0] if a else "")'; }
selector=$(card); [ -n "$selector" ] || { echo 'Не найдена доступная реплика; проверьте разметку интерфейса.' >&2; exit 1; }
tap "desc:$selector"; pause 3
adb_cmd shell input swipe 540 1450 540 650 400; pause 2; adb_cmd shell input swipe 540 650 540 1450 400; pause 2
tap 'desc:Говорящие'; wait_ui id:speaker_name_speaker_1 10; tap id:speaker_name_speaker_1
# ASCII input works on release too; Cyrillic injection in uia.py requires debug receiver.
adb_cmd shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_A; adb_cmd shell input text 'Murzik'; tap 'text:Готово'; pause 2
selector=$(card); [ -n "$selector" ] || { echo 'Не найдена реплика для пересчёта' >&2; exit 1; }
python3 "$uia" longtap "desc:$selector"; wait_ui id:btn_fragment_reprocess 10; tap id:btn_fragment_reprocess; wait_ui id:btn_fragment_start 10; tap id:btn_fragment_start
# Both outcomes are valid; the unchanged case may not write a new marker.
end=$((SECONDS+180))
while [ "$SECONDS" -lt "$end" ]; do
 if python3 "$uia" has 'text:Без изменений' || python3 "$uia" has 'text:обработан заново'; then break; fi
 rotate_if_needed; sleep 2
done
[ "$SECONDS" -lt "$end" ] || { echo 'Не дождались результата пересчёта' >&2; exit 1; }
pause 2; tap id:btn_export_json; pause 2
if python3 "$uia" has 'text:Save'; then tap 'text:Save'; elif python3 "$uia" has 'text:Сохранить'; then tap 'text:Сохранить'; else echo 'Не найдена кнопка сохранения SAF' >&2; exit 1; fi
pause 3; wait_ui id:btn_protocol 15; tap id:btn_protocol; wait_ui id:btn_make_protocol 15; tap id:btn_make_protocol; pause 20
stop_current
for f in "${recorded[@]}"; do adb_cmd pull "$f" "$out/$(basename "$f")"; done
if command -v ffmpeg >/dev/null; then
 list="$out/concat.txt"; : > "$list"
 for f in "$out"/*.mp4; do name=${f##*/}; printf "file '%s'\n" "$name" >> "$list"; done
 ffmpeg -nostdin -f concat -safe 0 -i "$list" -c copy "$out/demo-raw.mp4"
else echo 'ffmpeg не найден: части оставлены раздельно.'; fi
printf 'Готово: %s (без звука; режим полёта и DND не возвращаются автоматически)\n' "$out"
