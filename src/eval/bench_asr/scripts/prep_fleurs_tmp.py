"""Временный набор: FLEURS ru dev (официальный google/fleurs на HF), N фраз, формат docs/team/ARCHITECTURE_TEAM.md §2.
Запуск: python scripts/prep_fleurs_tmp.py --src <dir с dev.tar.gz и dev.tsv> --out <dataset_root> --n 200
"""
import argparse, csv, json, tarfile, io, random
from pathlib import Path
import numpy as np, soundfile as sf

ap = argparse.ArgumentParser()
ap.add_argument("--src", required=True)
ap.add_argument("--out", required=True)
ap.add_argument("--n", type=int, default=200)
ap.add_argument("--prefix", default="fleurs")
ap.add_argument("--lang", default="ru")
a = ap.parse_args()
src, out = Path(a.src), Path(a.out)
rows = {}
for line in open(src / "dev.tsv", encoding="utf-8"):
    p = line.rstrip("\n").split("\t")
    rows.setdefault(p[1], p)          # filename -> row; p[2]=raw transcription
import re
names = sorted(k for k in rows if not re.search(r"\d", rows[k][2]))   # без цифр: модели пишут числа словами
random.Random(0).shuffle(names)
want = set(names[: a.n])
(out / "audio").mkdir(parents=True, exist_ok=True)
(out / "ref").mkdir(exist_ok=True)
man = []
with tarfile.open(src / "dev.tar.gz") as tf:
    for m in tf:
        base = Path(m.name).name
        if base in want:
            data, sr = sf.read(io.BytesIO(tf.extractfile(m).read()), dtype="float32")   # FLEURS wav = float32
            if data.ndim > 1: data = data.mean(1)
            data = (np.clip(data, -1, 1) * 32767).astype("int16")
            assert sr == 16000, sr
            id_ = f"{a.prefix}_" + base[:-4]
            sf.write(out / "audio" / f"{id_}.wav", data, sr, subtype="PCM_16")
            dur = int(len(data) * 1000 / sr)
            text = rows[base][2]
            json.dump({"transcript": [{"speaker_id": "speaker_1", "start_ms": 0, "end_ms": dur, "text": text}]},
                      open(out / "ref" / f"{id_}.json", "w", encoding="utf-8"), ensure_ascii=False)
            man.append([id_, f"audio/{id_}.wav", f"ref/{id_}.json", dur, 1, a.lang, f"fleurs-{a.lang}-dev", "clean", "dev"])
with open(out / "manifest.csv", "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f); w.writerow("id,audio,ref,duration_ms,num_speakers,lang,source,condition,split".split(","))
    w.writerows(sorted(man))
print(len(man), "items,", sum(r[3] for r in man) / 1000, "s")
