#!/usr/bin/env bash
# Наполняет assets варианта моделями из общего кэша: $ROOT/.cache/models -> src/android/app/src/<tier>/assets/models/<tier>/
# Список файлов берётся из config/tiers/<tier>.json (те же пути, что читает приложение и Python-эталон):
# vad.model, audio.denoise.model, asr.model_dir, asr.second_pass.model_dir, asr_en.model_dir, language.lid_model,
# diarization.segmentation/embedding, punctuation.model, live.model_dir, llm.model. Рядом создаётся manifest.json набора (id, размеры, sha256).
#
#   scripts/stage_models.sh <lite|medium|hard|online> [--allow-missing] [--skip-llm]
#
# --allow-missing  не падать, если часть моделей ещё не скачана (набор будет неполным -- приложение покажет сообщение)
# --skip-llm       не копировать LLM (для быстрых проверок сборки)
# --map <путь-в-конфиге>=<другой путь в кэше>  подставить имеющийся файл вместо отсутствующего (для проверок; можно несколько раз),
#                                  например --map llm/qwen3-0.6b-q4_0.gguf=llm-qwen3-0.6b-q4_k_m/Qwen3-0.6B-Q4_K_M.gguf
# MODELS_ROOT=/другой/каталог  переопределяет $ROOT/.cache/models
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "$HERE/env.sh"

TIER="${1:-}"
shift || true
ALLOW_MISSING=0
SKIP_LLM=0
MAPS=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --allow-missing) ALLOW_MISSING=1 ;;
    --skip-llm) SKIP_LLM=1 ;;
    --map) shift; MAPS+=("${1:?--map требует путь=альтернатива}") ;;
    *) echo "неизвестный аргумент: $1" >&2; exit 2 ;;
  esac
  shift
done
# alt_for <путь>: альтернативный путь из --map (пусто, если не задан)
alt_for() {
  local m
  for m in ${MAPS[@]+"${MAPS[@]}"}; do
    [ "${m%%=*}" = "$1" ] && { echo "${m#*=}"; return 0; }
  done
  return 0
}
case "$TIER" in lite|medium|hard|online) ;; *) echo "usage: $0 <lite|medium|hard|online> [--allow-missing] [--skip-llm]" >&2; exit 2 ;; esac

REPO="$(cd "$HERE/.." && pwd)"
DEST_ASSETS="$REPO/src/android/app/src/$TIER/assets"
DEST="$DEST_ASSETS/models/$TIER"
MODELS_ROOT="${MODELS_ROOT:-$ROOT/.cache/models}"

if [ "$TIER" = "online" ]; then
  rm -rf "$DEST_ASSETS/models"
  mkdir -p "$DEST_ASSETS"
  echo "online: встроенных моделей нет (скачиваются менеджером моделей). assets очищены."
  exit 0
fi

CONFIG="$REPO/config/tiers/$TIER.json"
[ -f "$CONFIG" ] || { echo "нет конфига $CONFIG" >&2; exit 1; }
[ -d "$MODELS_ROOT" ] || { echo "нет каталога моделей $MODELS_ROOT" >&2; exit 1; }

# Список относительных путей из конфига (JSON читаем стандартным python3, без внешних зависимостей)
mapfile_paths() {
  python3 -I - "$CONFIG" "$SKIP_LLM" <<'PY'
import json, sys
cfg = json.load(open(sys.argv[1], encoding="utf-8"))
skip_llm = sys.argv[2] == "1"
out = []
def add(p):
    if p and p not in out:
        out.append(p)
add(cfg.get("vad", {}).get("model"))
dn = cfg.get("audio", {}).get("denoise", {}) or {}
if dn.get("enabled"):
    add(dn.get("model"))
asr = cfg.get("asr", {}) or {}
add(asr.get("model_dir"))
add(((asr.get("second_pass") or {}).get("model_dir")))
en = cfg.get("asr_en") or {}
add(en.get("model_dir"))
add(((en.get("second_pass") or {}).get("model_dir")))
lang = cfg.get("language") or {}
if str(lang.get("mode", "ru")).lower() == "auto":
    add(lang.get("lid_model"))
dia = cfg.get("diarization", {}) or {}
add(dia.get("segmentation"))
add(dia.get("embedding"))
add((cfg.get("punctuation") or {}).get("model"))
live = cfg.get("live") or {}
if live.get("enabled"):
    add(live.get("model_dir"))
if not skip_llm:
    add((cfg.get("llm") or {}).get("model"))
print("\n".join(out))
PY
}

PATHS=()
while IFS= read -r line; do [ -n "$line" ] && PATHS+=("$line"); done < <(mapfile_paths)

# Необязательная модель живой расшифровки (live.model_dir): если её нет в кэше -- предупреждение, набор собирается без неё
# (превью выключится само). Остальные отсутствующие модели -- ошибка, как раньше.
LIVE_PATH="$(python3 -I -c 'import json,sys; c=json.load(open(sys.argv[1],encoding="utf-8")); l=c.get("live") or {}; print(l.get("model_dir") or "" if l.get("enabled") else "")' "$CONFIG")"
MISSING=()
for p in "${PATHS[@]}"; do
  alt="$(alt_for "$p")"
  [ -e "$MODELS_ROOT/$p" ] || { [ -n "$alt" ] && [ -e "$MODELS_ROOT/$alt" ]; } && continue
  if [ -n "$LIVE_PATH" ] && [ "$p" = "$LIVE_PATH" ]; then
    echo "ВНИМАНИЕ: нет модели живой расшифровки $p -- набор без превью записи (см. --map)." >&2
    continue
  fi
  MISSING+=("$p")
done
if [ "${#MISSING[@]}" -gt 0 ]; then
  echo "Не найдены модели в $MODELS_ROOT:" >&2
  printf '  %s\n' "${MISSING[@]}" >&2
  if [ "$ALLOW_MISSING" -ne 1 ]; then
    echo "Скачайте их (scripts/fetch_models.py) или запустите с --allow-missing." >&2
    exit 1
  fi
  echo "ВНИМАНИЕ: --allow-missing -- набор будет неполным." >&2
fi

rm -rf "$DEST"
mkdir -p "$DEST"
for p in "${PATHS[@]}"; do
  src="$MODELS_ROOT/$p"
  if [ ! -e "$src" ]; then
    alt="$(alt_for "$p")"
    [ -n "$alt" ] && [ -e "$MODELS_ROOT/$alt" ] && src="$MODELS_ROOT/$alt" || continue
  fi
  mkdir -p "$DEST/$(dirname "$p")"
  # APFS-клон (мгновенно и без дублирования места), иначе обычное копирование; имя берётся из пути конфига
  cp -cR "$src" "$DEST/$p" 2>/dev/null || cp -R "$src" "$DEST/$p"
done

# manifest.json: файлы с размерами и sha256 (приложение проверяет размеры при установке в filesDir)
python3 -I - "$DEST" "$TIER" <<'PY'
import hashlib, json, os, sys
dest, tier = sys.argv[1], sys.argv[2]
files = []
for base, _, names in os.walk(dest):
    for n in sorted(names):
        if n == "manifest.json" or n.startswith("."):
            continue
        full = os.path.join(base, n)
        rel = os.path.relpath(full, dest).replace(os.sep, "/")
        h = hashlib.sha256()
        with open(full, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        files.append({"path": rel, "size": os.path.getsize(full), "sha256": h.hexdigest()})
files.sort(key=lambda e: e["path"])
manifest = {"id": tier, "tier": tier, "version": "1", "builtin": True, "files": files}
with open(os.path.join(dest, "manifest.json"), "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
total = sum(e["size"] for e in files)
print(f"manifest: {len(files)} файлов, {total / 1048576:.1f} МБ")
PY
echo "готово: $DEST"
