#!/usr/bin/env bash
# Последовательная очередь тяжёлых задач: одна строка jobs/jobs.txt = одна команда (выполняется в bench_asr/).
# Дописывать строки можно в любой момент; строка EXIT останавливает раннер. Индекс — jobs/idx.
cd "$(dirname "$0")/.."
export OMP_NUM_THREADS=4 ORT_NUM_THREADS=4 OPENBLAS_NUM_THREADS=4 VECLIB_MAXIMUM_THREADS=4
mkdir -p jobs; [ -f jobs/idx ] || echo 0 > jobs/idx; touch jobs/jobs.txt
while true; do
  n=$(cat jobs/idx); line=$(sed -n "$((n+1))p" jobs/jobs.txt)
  if [ -z "$line" ]; then sleep 15; continue; fi
  [ "$line" = "EXIT" ] && break
  echo "[$(date +%H:%M:%S)] #$n $line" >> jobs/queue.log
  bash -c "$line" >> jobs/queue.log 2>&1
  echo "[$(date +%H:%M:%S)] done #$n rc=$?" >> jobs/queue.log
  echo $((n+1)) > jobs/idx
done
