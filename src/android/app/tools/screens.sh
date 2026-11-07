#!/usr/bin/env bash
# Снимает скриншоты основных экранов medium-debug на запущенном эмуляторе (нужна готовая история с записью cats_c01).
#   src/android/app/tools/screens.sh [каталог-вывода]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../../../../scripts/env.sh"
U="$HERE/uia.py"; S="${1:-$(cd "$HERE/../../../.." && pwd)/artifacts/demo/screens}"; PKG=ru.neurosteno.app.medium
mkdir -p "$S"
t() { python3 "$U" tap "$@"; }
w() { python3 "$U" wait "$@"; }
shot() { python3 "$U" shot "$S/$1.png"; }
adb shell am force-stop $PKG; adb shell am start -n $PKG/ru.neurosteno.app.MainActivity >/dev/null; w id:btn_pick_audio 20; sleep 1
shot 01_home
t text:cats_c01_v0; w id:btn_export_json 10; sleep 1
shot 03_transcript
t text:"Другие форматы"; sleep 2; shot 06_export_formats; adb shell input keyevent KEYCODE_BACK; sleep 1
t desc:"Говорящие"; w id:speaker_name_speaker_1 5; t id:speaker_name_speaker_1
python3 "$U" type "Мурзик"; sleep 1; shot 07_speakers_rename; t text:Готово; sleep 1
t desc:"Поиск по тексту"; w id:search_field 5; python3 "$U" type "кот"; sleep 1; shot 05_search
t desc:"Закрыть поиск"; sleep 1
t desc:"Действия с репликой"; sleep 1; shot 08_utterance_menu
t text:"Править текст"; w id:edit_text_field 5; sleep 1; shot 09_edit_dialog; t text:Отмена; sleep 1
t text:"здравствуйте"; sleep 3; shot 04_transcript_playing
t id:btn_play_pause; sleep 1
