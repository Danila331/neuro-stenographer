#!/usr/bin/env bash
# Peak RSS / dirty footprint of llama-server for flag combinations (one server at a time).
cd "$(dirname "$0")/.."
PY=.venv/bin/python
for m in "$@"; do
  $PY -m bench_llm.run_mem --models $m --ctx 2048 4096 --tag _A_default
  $PY -m bench_llm.run_mem --models $m --ctx 2048 4096 --flags=-nr --tag _B_norepack
  $PY -m bench_llm.run_mem --models $m --ctx 2048 4096 --flags="-nr -lm none" --tag _C_norepack_nommap
  $PY -m bench_llm.run_mem --models $m --ctx 2048 4096 --kv q8_0 --flags=-nr --tag _D_norepack_kvq8
  $PY -m bench_llm.run_mem --models $m --ctx 2048 4096 --kv q8_0 --flags="-nr -lm none" --tag _E_norepack_nommap_kvq8
done
