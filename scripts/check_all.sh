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
for part in stenoeval refpipe datasets bench_diar bench_llm; do
  dir="$root/src/eval/$part"; py="$dir/.venv/bin/python"
  if [ -x "$py" ]; then
    if [ "$part" = refpipe ]; then run_check "python:$part" bash -c 'cd "$1" && "$2" -m pytest -q --ignore=tests/test_e2e.py -k "not e2e"' _ "$dir" "$py"
    else run_check "python:$part" bash -c 'cd "$1" && PYTHONPATH=. "$2" -m pytest -q' _ "$dir" "$py"; fi
  else skip "python:$part" 'нет .venv'; fi
done
if [ "$android" = 1 ]; then
  if source "$root/scripts/env.sh"; then
    if [ "$fast" = 1 ]; then tasks=(':core:test'); else tasks=(':core:test' ':app:testMediumDebugUnitTest'); fi
    run_check android bash -c 'cd "$1" && shift && ./gradlew --offline -Dorg.gradle.workers.max=2 "$@"' _ "$root/src/android" "${tasks[@]}"
    (cd "$root/src/android" && ./gradlew --stop) || echo 'Не удалось остановить Gradle daemon' >&2
  else skip android 'ошибка окружения'; failed=1; fi
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
