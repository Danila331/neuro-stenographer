#!/usr/bin/env bash
# Atomic download of diarization models into shared cache. Usage: fetch_models.sh [root]
set -euo pipefail
ROOT="${1:-$(cd "$(dirname "$0")/../../../../.." && pwd)}"
M="$ROOT/.cache/models"; mkdir -p "$M"
REL=https://github.com/k2-fsa/sherpa-onnx/releases/download
for seg in sherpa-onnx-pyannote-segmentation-3-0 sherpa-onnx-reverb-diarization-v1; do
  [ -d "$M/$seg" ] && continue
  T="$M/.tmp-diar-bench-$seg"; rm -rf "$T"; mkdir -p "$T"
  curl -fsSL "$REL/speaker-segmentation-models/$seg.tar.bz2" -o "$T/a.tar.bz2"
  tar -xjf "$T/a.tar.bz2" -C "$T" && rm "$T/a.tar.bz2"
  mv "$T/$seg" "$M/$seg"; rmdir "$T"
done
EMBS="3dspeaker_speech_campplus_sv_en_voxceleb_16k 3dspeaker_speech_campplus_sv_zh-cn_16k-common 3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced 3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common 3dspeaker_speech_eres2net_base_200k_sv_zh-cn_16k-common 3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k 3dspeaker_speech_eres2net_large_sv_zh-cn_3dspeaker_16k 3dspeaker_speech_eres2net_sv_en_voxceleb_16k nemo_en_titanet_small nemo_en_titanet_large wespeaker_en_voxceleb_CAM++ wespeaker_en_voxceleb_resnet34 wespeaker_en_voxceleb_resnet34_LM wespeaker_en_voxceleb_resnet293_LM wespeaker_zh_cnceleb_resnet34"
for e in $EMBS; do
  [ -d "$M/diar-emb-$e" ] && continue
  T="$M/.tmp-diar-bench-$e"; rm -rf "$T"; mkdir -p "$T"
  curl -fsSL "$REL/speaker-recongition-models/$e.onnx" -o "$T/model.onnx"
  mv "$T" "$M/diar-emb-$e"
done
