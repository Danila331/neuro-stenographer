#!/usr/bin/env bash
# Ждёт, пока load average (1 мин) < $1 (по умолчанию 12), не дольше $2 секунд (по умолчанию 1800). Код возврата 0 — всегда
# (если не дождались — пишет UNRELIABLE в /tmp/asr_bench_unreliable, чтобы пометить цифры скорости).
T=${1:-12}; MAXW=${2:-1800}; w=0
rm -f /tmp/asr_bench_unreliable
while true; do
  la=$(sysctl -n vm.loadavg | awk '{print $2}' | tr , .)
  if awk "BEGIN{exit !($la < $T)}"; then echo "load=$la ok"; exit 0; fi
  [ $w -ge $MAXW ] && { echo "load=$la still high after ${MAXW}s -> UNRELIABLE"; echo "load=$la" > /tmp/asr_bench_unreliable; exit 0; }
  sleep 20; w=$((w+20))
done
