#!/usr/bin/env bash
# Atomic download of official GGUF models into the shared cache ($ROOT/.cache/models/llm-<name>/).
# Only public, non-gated official repositories (Qwen, Vikhrmodels). No tokens.
set -euo pipefail
ROOT="${ROOT:-/Users/danilka2203/Desktop/work/neuro-stenographer-claude}"
M="$ROOT/.cache/models"
mkdir -p "$M"
fetch() { # name repo file (runs as background job)
  local name="$1" repo="$2" file="$3"
  local dst="$M/llm-$name"
  if [ -f "$dst/$file" ]; then echo "have $name"; return; fi
  local tmp="$M/.tmp-llm-bench-$name"; mkdir -p "$tmp"
  echo "fetch $name"
  local n=0
  until curl -fL --retry 3 --retry-all-errors --connect-timeout 20 -C - -o "$tmp/$file" "https://huggingface.co/$repo/resolve/main/$file"; do
    n=$((n+1)); [ $n -ge 40 ] && { echo "FAILED $name"; return 1; }; sleep 3
  done
  mv "$tmp" "$dst"; echo "done $name"
}
fetch qwen3-0.6b-q8_0        Qwen/Qwen3-0.6B-GGUF            Qwen3-0.6B-Q8_0.gguf &
while [ "$(jobs -rp | wc -l)" -ge "${PAR:-4}" ]; do sleep 2; done
fetch qwen3-1.7b-q8_0        Qwen/Qwen3-1.7B-GGUF            Qwen3-1.7B-Q8_0.gguf &
while [ "$(jobs -rp | wc -l)" -ge "${PAR:-4}" ]; do sleep 2; done
fetch qwen3-4b-q4_k_m        Qwen/Qwen3-4B-GGUF              Qwen3-4B-Q4_K_M.gguf &
while [ "$(jobs -rp | wc -l)" -ge "${PAR:-4}" ]; do sleep 2; done
fetch qwen2.5-0.5b-q4_k_m    Qwen/Qwen2.5-0.5B-Instruct-GGUF qwen2.5-0.5b-instruct-q4_k_m.gguf &
while [ "$(jobs -rp | wc -l)" -ge "${PAR:-4}" ]; do sleep 2; done
fetch qwen2.5-0.5b-q8_0      Qwen/Qwen2.5-0.5B-Instruct-GGUF qwen2.5-0.5b-instruct-q8_0.gguf &
while [ "$(jobs -rp | wc -l)" -ge "${PAR:-4}" ]; do sleep 2; done
fetch qwen2.5-1.5b-q4_k_m    Qwen/Qwen2.5-1.5B-Instruct-GGUF qwen2.5-1.5b-instruct-q4_k_m.gguf &
while [ "$(jobs -rp | wc -l)" -ge "${PAR:-4}" ]; do sleep 2; done
fetch qwen2.5-1.5b-q8_0      Qwen/Qwen2.5-1.5B-Instruct-GGUF qwen2.5-1.5b-instruct-q8_0.gguf &
while [ "$(jobs -rp | wc -l)" -ge "${PAR:-4}" ]; do sleep 2; done
fetch qwen2.5-3b-q4_k_m      Qwen/Qwen2.5-3B-Instruct-GGUF   qwen2.5-3b-instruct-q4_k_m.gguf &
while [ "$(jobs -rp | wc -l)" -ge "${PAR:-4}" ]; do sleep 2; done
fetch vikhr-qwen2.5-0.5b-q4_k_m Vikhrmodels/Vikhr-Qwen-2.5-0.5B-instruct-GGUF Vikhr-Qwen-2.5-0.5B-instruct-Q4_K_M.gguf &
while [ "$(jobs -rp | wc -l)" -ge "${PAR:-4}" ]; do sleep 2; done
fetch vikhr-qwen2.5-1.5b-q4_k_m Vikhrmodels/Vikhr-Qwen-2.5-1.5B-Instruct-GGUF Vikhr-Qwen-2.5-1.5b-Instruct-Q4_K_M.gguf &
while [ "$(jobs -rp | wc -l)" -ge "${PAR:-4}" ]; do sleep 2; done
fetch qvikhr3-1.7b-q4_k_m    Vikhrmodels/QVikhr-3-1.7B-Instruction-noreasoning-GGUF QVikhr-3-1.7B-Instruction-noreasoning-Q4_K_M.gguf &
while [ "$(jobs -rp | wc -l)" -ge "${PAR:-4}" ]; do sleep 2; done
fetch qvikhr3-4b-q4_k_m      Vikhrmodels/QVikhr-3-4B-Instruction-GGUF QVikhr-3-4B-Instruction-Q4_K_M.gguf &
while [ "$(jobs -rp | wc -l)" -ge "${PAR:-4}" ]; do sleep 2; done
wait
echo ALLDONE
