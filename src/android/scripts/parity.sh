#!/usr/bin/env bash
# Паритет Android <-> refpipe: прогоняет N dev-файлов датасета через адаптеры Android (tools/jvm-smoke, те же классы, что
# на телефоне) и через Python-эталон refpipe на ТОМ ЖЕ конфиге, считает `stenoeval score` для обоих и ПАДАЕТ, если
# WER norm Android хуже refpipe более чем на 1 п.п. (порог: PARITY_TOL, по умолчанию 1.0).
#
#   src/android/scripts/parity.sh <dataset> <N> [tier]
#   <dataset> -- имя каталога в $ROOT/.cache/data (например ru-dialog-tts-cats) или путь; tier по умолчанию medium.
# Без пунктуации/LLM (punctuation.source=none), язык ru (--language ru), обе стороны с одним числом потоков (PARITY_THREADS).
# Результаты: $ROOT/.cache/parity/<dataset>-<tier>/{android,refpipe}/ (+ summary.txt). Нужен venv refpipe:
#   cd src/eval/refpipe && uv venv --python 3.11 .venv && uv pip install --python .venv/bin/python -e ".[dev]"
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../../scripts/env.sh
source "$HERE/../../../scripts/env.sh"
REPO="$(cd "$HERE/../../.." && pwd)"

DATASET="${1:?usage: $0 <dataset> <N> [tier]}"
N="${2:?usage: $0 <dataset> <N> [tier]}"
TIER="${3:-medium}"
TOL="${PARITY_TOL:-1.0}"
THREADS="${PARITY_THREADS:-4}"
[ -d "$DATASET" ] || DATASET="$NEUROSTENO_ROOT/.cache/data/$DATASET"
[ -f "$DATASET/manifest.csv" ] || { echo "нет $DATASET/manifest.csv" >&2; exit 2; }
NAME="$(basename "$DATASET")"
OUT="$NEUROSTENO_ROOT/.cache/parity/$NAME-$TIER"
CONFIG="$REPO/config/tiers/$TIER.json"
MODELS="$NEUROSTENO_ROOT/.cache/models"
REFPIPE="$REPO/src/eval/refpipe/.venv/bin/refpipe"
[ -x "$REFPIPE" ] || { echo "нет venv refpipe (см. заголовок скрипта)" >&2; exit 2; }

# первые N dev-файлов по id
IDS=()
while IFS= read -r line; do IDS+=("$line"); done < <(python3 -I - "$DATASET/manifest.csv" "$N" <<'PY'
import csv, sys
rows = [r for r in csv.DictReader(open(sys.argv[1], encoding="utf-8")) if r.get("split") == "dev"]
rows.sort(key=lambda r: r["id"])
for r in rows[: int(sys.argv[2])]:
    print(r["id"])
PY
)
[ "${#IDS[@]}" -gt 0 ] || { echo "в $DATASET нет dev-файлов" >&2; exit 2; }
echo "датасет $NAME, tier $TIER, файлов ${#IDS[@]}: ${IDS[*]}"

rm -rf "$OUT"; mkdir -p "$OUT/android" "$OUT/refpipe"
for id in "${IDS[@]}"; do
  wav="$DATASET/audio/$id.wav"
  echo "--- $id"
  "$REFPIPE" run --config "$CONFIG" --models "$MODELS" --input "$wav" --out "$OUT/refpipe" --language ru \
    --num-threads "$THREADS" --override punctuation.source=none >/dev/null
  PARITY_THREADS="$THREADS" "$HERE/jvm_smoke.sh" "$wav" "$TIER" "$OUT/android" >/dev/null 2>&1 \
    || { echo "jvm_smoke упал на $id" >&2; exit 1; }
done

IDLIST="$(IFS=,; echo "${IDS[*]}")"
score() { # <run-dir> -> WER norm
  (cd "$REPO/src/eval/stenoeval" && uv run --quiet stenoeval score --dataset "$DATASET" --run "$1" --ids "$IDLIST" --split all 2>&1 \
    | sed -n 's/.*WER norm = \([0-9.]*\)%.*/\1/p' | head -1)
}
A="$(score "$OUT/android")"
R="$(score "$OUT/refpipe")"
{
  echo "dataset=$NAME tier=$TIER files=${#IDS[@]} tol=${TOL}pp"
  echo "WER norm: android=${A}%  refpipe=${R}%  diff=$(python3 -I -c "print(round($A-$R,2))")pp"
} | tee "$OUT/summary.txt"
python3 -I -c "import sys; sys.exit(0 if $A - $R <= $TOL else 1)" || { echo "ПАРИТЕТ НАРУШЕН: Android хуже refpipe более чем на ${TOL} п.п." >&2; exit 1; }
echo "паритет ок"
