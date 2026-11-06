#!/usr/bin/env bash
# Создаёт две AVD, имитирующие проверку жюри (ARM, без GPU-ускорения), в $ANDROID_AVD_HOME ($ROOT/.local/avd):
#   jury-4g: 4096 МБ RAM, 4 ядра (основная для смоука)      jury-2g: 2048 МБ RAM, 2 ядра (стресс-тест)
# Образ: system-images;android-35;google_apis;arm64-v8a. Нужны: sdk_setup.sh, ранее принятые лицензии.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../../scripts/env.sh
source "$HERE/../../../scripts/env.sh"

IMAGE="system-images;android-35;google_apis;arm64-v8a"
[ -d "$ANDROID_HOME/system-images/android-35/google_apis/arm64-v8a" ] || { echo "нет $IMAGE -- сначала sdk_setup.sh" >&2; exit 1; }

make_avd() { # <имя> <RAM МБ> <ядра>
  local name="$1" ram="$2" cores="$3"
  echo no | avdmanager create avd --force -n "$name" -k "$IMAGE" -d pixel_6 >/dev/null
  local ini="$ANDROID_AVD_HOME/$name.avd/config.ini"
  set_key() { # ключ значение: заменить или дописать
    if grep -q "^$1=" "$ini"; then sed -i.bak "s|^$1=.*|$1=$2|" "$ini"; else echo "$1=$2" >> "$ini"; fi
  }
  set_key hw.ramSize "$ram"
  set_key hw.cpu.ncore "$cores"
  set_key hw.gpu.enabled yes
  set_key hw.gpu.mode swiftshader_indirect   # программный рендер, без GPU хоста
  set_key disk.dataPartition.size 12G        # место под APK + распакованные модели
  set_key hw.keyboard yes
  set_key vm.heapSize 512
  rm -f "$ini.bak"
  echo "AVD $name: RAM ${ram} МБ, ядер $cores"
}

make_avd jury-4g 4096 4
make_avd jury-2g 2048 2
