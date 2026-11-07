#!/usr/bin/env bash
# Offline checks; each failure is reported without stopping subsequent checks.
set -u
root=$(cd "$(dirname "$0")/.." && pwd)
fast=0; android=1
for arg in "$@"; do case "$arg" in --fast) fast=1 ;; --no-android) android=0 ;; -h|--help) echo 'Usage: check_all.sh [--fast] [--no-android]'; exit 0 ;; *) echo "Unknown option: $arg" >&2; exit 2 ;; esac; done
names=(); states=(); times=(); failed=0
run_check() {
  local label=$1 start rc; shift; start=$SECONDS
  printf '\n>>> %s\n' "$label"
  if "$@"; then rc=PASS; else rc=FAIL; failed=1; fi
  names+=("$label"); states+=("$rc"); times+=("$((SECONDS-start))")
}
skip() { names+=("$1"); states+=(SKIP); times+=(0); printf '\n>>> %s: SKIP (%s)\n' "$1" "$2"; }
# Создаёт .venv проекта и ставит пакет с pytest, если их ещё нет (нужен uv; после первой установки работает офлайн).
ensure_venv() {
  local dir=$1 py=$2
  if [ -x "$py" ] && "$py" -c 'import pytest' 2>/dev/null; then return 0; fi
  if ! command -v uv >/dev/null 2>&1; then echo "uv не найден: .venv для $(basename "$dir") не создан" >&2; return 0; fi
  echo ">>> подготовка .venv: $(basename "$dir")"
  [ -x "$py" ] || uv venv --python 3.11 "$dir/.venv" >&2 || return 0
  (cd "$dir" && { uv pip install --python "$py" -e ".[dev]" >&2 || uv pip install --python "$py" -e . pytest >&2; }) \
    || echo "не удалось установить зависимости для $(basename "$dir")" >&2
}
# Подтягивает Android-зависимости из общего кэша (.cache/android-deps; при первом запуске скачивает и проверяет sha256).
ensure_android_deps() {
  local a="$root/src/android"
  [ -d "$a/third_party/maven" ] || "$a/scripts/fetch_sherpa_onnx.sh" >&2 || return 1
  [ -d "$a/engine-llm/src/main/cpp/llama.cpp" ] || "$a/scripts/fetch_llama_cpp.sh" >&2 || return 1
}
for part in stenoeval refpipe datasets bench_diar bench_llm; do
  dir="$root/src/eval/$part"; py="$dir/.venv/bin/python"
  ensure_venv "$dir" "$py"
  if [ -x "$py" ] && "$py" -c 'import pytest' 2>/dev/null; then
    if [ "$part" = refpipe ]; then run_check "python:$part" bash -c 'cd "$1" && "$2" -m pytest -q --ignore=tests/test_e2e.py -k "not e2e"' _ "$dir" "$py"
    else run_check "python:$part" bash -c 'cd "$1" && PYTHONPATH=. "$2" -m pytest -q' _ "$dir" "$py"; fi
  else skip "python:$part" 'нет .venv или pytest (нужен uv и сеть для первой установки)'; fi
done
if [ "$android" = 1 ]; then
  if source "$root/scripts/env.sh" && ensure_android_deps; then
    if [ "$fast" = 1 ]; then tasks=(':core:test'); else tasks=(':core:test' ':app:testMediumDebugUnitTest'); fi
    run_check android bash -c 'cd "$1" && shift && ./gradlew --offline -Dorg.gradle.workers.max=2 "$@"' _ "$root/src/android" "${tasks[@]}"
    (cd "$root/src/android" && ./gradlew --stop) || echo 'Не удалось остановить Gradle daemon' >&2
  else skip android 'ошибка окружения или загрузки зависимостей'; failed=1; fi
else skip android '--no-android'; fi
if [ "$fast" = 0 ]; then
  run_check models python3 "$root/scripts/fetch_models.py" --verify --tier all
  validator="$root/src/eval/stenoeval/.venv/bin/stenoeval"
  if [ ! -x "$validator" ]; then skip json 'нет stenoeval в .venv'
  else
    jsonlist=$(mktemp "${TMPDIR:-/tmp}/neuro-json.XXXXXX")
    find "$root/artifacts/test-results" -type f -name '*.json' ! -name '*.full.json' ! -name '*.done.json' ! -name '*.llm.json' -print0 > "$jsonlist"
    if [ ! -s "$jsonlist" ]; then skip json 'нет JSON'; else run_check json xargs -0 -n 1 "$validator" validate < "$jsonlist"; fi
    rm -f "$jsonlist"
  fi
fi
printf '\n%-24s %-5s %s\n' CHECK RESULT SECONDS
for ((i=0;i<${#names[@]};i++)); do printf '%-24s %-5s %s\n' "${names[i]}" "${states[i]}" "${times[i]}"; done
exit "$failed"
