#!/usr/bin/env bash
# Проверка кнопки/жеста «Назад» на всех экранах (enableOnBackInvokedCallback + BackHandler). Нужна история с записью cats_c01.
#   src/android/app/tools/back_check.sh
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../../../../scripts/env.sh"
U="$HERE/uia.py"; PKG="${PKG:-ru.neurosteno.app.medium}"; FAIL=0
back() { adb shell input keyevent KEYCODE_BACK; sleep 1.2; }
tap() { python3 "$U" tap "$@" >/dev/null; sleep 1.2; }
has() { python3 "$U" has "$@"; }
swipe() { adb shell input swipe 540 1900 540 500 300; sleep 1; }
check() { # описание, команда проверки...
  local d="$1"; shift
  if "$@"; then echo "PASS  $d"; else echo "FAIL  $d"; FAIL=1; fi
}
fg() { adb shell dumpsys activity activities | grep -m1 "topResumedActivity" | grep -q "$PKG"; }
adb shell am force-stop $PKG; adb shell am start -n $PKG/ru.neurosteno.app.MainActivity >/dev/null; python3 "$U" wait id:btn_pick_audio 20; sleep 1
tap desc:"Настройки"; check "Настройки открыты" has text:"Тема оформления"; back; check "Настройки -> Главный" has id:btn_pick_audio
tap desc:"Модели"; check "Модели открыты" has text:"Все вычисления"; back; check "Модели -> Главный" has id:btn_pick_audio
tap desc:"Настройки"; swipe; swipe; tap id:btn_open_stats; check "Статистика открыта" has text:"Статистика обработки"; back; check "Статистика -> Настройки" has text:"Тема оформления"
swipe; swipe; tap id:btn_open_models; check "Настройки -> Модели" has text:"Все вычисления"; back; check "Модели -> Настройки" has text:"Тема оформления"; back
tap id:btn_record; check "Запись открыта" has id:btn_start_record; back; check "Запись -> Главный" has id:btn_pick_audio
tap text:cats_c01_v0; python3 "$U" wait id:btn_export_json 10 >/dev/null; check "Расшифровка открыта" has id:btn_export_json
tap desc:"Поиск по тексту"; check "Поиск открыт" has id:search_field; back  # первый «Назад» скрывает клавиатуру
back; check "Назад закрывает поиск, экран остаётся" has id:btn_export_json; check "  поле поиска закрыто" bash -c '! python3 "'"$U"'" has id:search_field'
tap text:"Другие форматы"; check "Лист форматов открыт" has text:"Экспорт и отправка"; back; check "Назад закрывает лист, экран остаётся" has id:btn_export_json
tap desc:"Говорящие"; check "Диалог говорящих открыт" has text:"Готово"; back; check "Назад закрывает диалог" has id:btn_export_json
tap id:btn_protocol; check "Протокол открыт" has id:tab_protocol; back; check "Протокол -> Расшифровка" has id:btn_export_json
back; check "Расшифровка -> Главный" has id:btn_pick_audio
back; sleep 1; if fg; then echo "FAIL  Назад на главном не уходит из приложения"; FAIL=1; else echo "PASS  Назад на главном выходит из приложения"; fi
exit $FAIL
