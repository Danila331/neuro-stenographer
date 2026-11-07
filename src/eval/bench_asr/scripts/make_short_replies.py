"""Короткие реплики («да», «угу», «ага», «мяу», ...) через Piper ru_RU (голоса и обвязка — из пакета stenodata агента datasets).
Запуск интерпретатором venv агента datasets (там piper-tts):
  <ROOT>/.worktrees/datasets/src/eval/datasets/.venv/bin/python scripts/make_short_replies.py --out <ROOT>/.cache/data/asr-bench-short-tmp
"""
import argparse, csv, json, sys
from pathlib import Path
import numpy as np, soundfile as sf

ap = argparse.ArgumentParser(); ap.add_argument("--out", required=True)
ap.add_argument("--stenodata", default=str(Path(__file__).resolve().parents[5] / "datasets/src/eval/datasets"))
a = ap.parse_args()
sys.path.insert(0, a.stenodata)
from stenodata.build_tts import Voices  # noqa: E402

PHRASES = ["да", "нет", "ага", "угу", "ну", "ладно", "хорошо", "понял", "мяу", "точно", "согласен", "конечно", "ясно",
           "ну да", "так", "верно", "спасибо", "привет", "пока", "что"]
SR = 16000
v = Voices(use_say=False)
out = Path(a.out); (out / "audio").mkdir(parents=True, exist_ok=True); (out / "ref").mkdir(exist_ok=True)
man = []
for vi, voice in enumerate(v.names):
    for pi, ph in enumerate(PHRASES):
        x = v.synth(voice, ph + ".", length_scale=1.0)
        pad = np.zeros(int(0.6 * SR), np.float32)
        y = np.concatenate([pad, x, pad]); y = y / (np.abs(y).max() + 1e-9) * 0.5
        id_ = f"short_{voice}_{pi:02d}"
        sf.write(out / "audio" / f"{id_}.wav", (y * 32767).astype("int16"), SR, subtype="PCM_16")
        dur = int(len(y) / SR * 1000)
        json.dump({"transcript": [{"speaker_id": "speaker_1", "start_ms": 600, "end_ms": dur - 600 if dur > 1300 else dur - 1, "text": ph}]},
                  open(out / "ref" / f"{id_}.json", "w", encoding="utf-8"), ensure_ascii=False)
        man.append([id_, f"audio/{id_}.wav", f"ref/{id_}.json", dur, 1, "ru", f"piper-{voice}", "clean", "dev"])
with open(out / "manifest.csv", "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f); w.writerow("id,audio,ref,duration_ms,num_speakers,lang,source,condition,split".split(",")); w.writerows(man)
print(len(man), "clips; voices:", v.names)
