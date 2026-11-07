"""Длинные «записи» из FLEURS-фраз с известными границами реплик (для теста чанков и временных меток).
Границы реплик — по Silero VAD (обрезка тишины/шума с краёв фразы), точность ~±100 мс.
python scripts/build_long.py --src <dataset_root> --out <out_root> --n-rec 4 --per-rec 14
"""
import argparse, csv, json, random
from pathlib import Path
import numpy as np, soundfile as sf

ap = argparse.ArgumentParser()
ap.add_argument("--src", required=True); ap.add_argument("--out", required=True)
ap.add_argument("--n-rec", type=int, default=4); ap.add_argument("--per-rec", type=int, default=14)
ap.add_argument("--seed", type=int, default=1)
a = ap.parse_args()
src, out = Path(a.src), Path(a.out)
rows = list(csv.DictReader(open(src / "manifest.csv", encoding="utf-8")))
rng = random.Random(a.seed); rng.shuffle(rows)
(out / "audio").mkdir(parents=True, exist_ok=True); (out / "ref").mkdir(exist_ok=True)
SR = 16000


def trim(x, pad=0.05):
    """-> (start_s, end_s) речи в x по Silero VAD (энергетический порог на FLEURS ненадёжен: фоновый шум)."""
    from bench_asr.vad import vad_segments
    segs = vad_segments(x, threshold=0.5, min_silence=0.5, min_speech=0.1, pad_ms=0)
    return max(0, segs[0][0] / SR - pad), min(len(x) / SR, segs[-1][1] / SR + pad)


man = []
k = 0
for r in range(a.n_rec):
    parts, utts, t = [], [], 0.0
    for q in range(a.per_rec):
        row = rows[k]; k += 1
        x, sr = sf.read(src / row["audio"], dtype="float32")
        s, e = trim(x)
        seg = x[int(s * SR): int(e * SR)]
        gap = rng.uniform(0.3, 1.2)
        g = (np.random.RandomState(k).randn(int(gap * SR)) * 1e-4).astype("float32")
        if parts: parts.append(g); t += gap
        text = json.load(open(src / row["ref"], encoding="utf-8"))["transcript"][0]["text"]
        utts.append({"speaker_id": f"speaker_{1 + q % 2}", "start_ms": int(t * 1000), "end_ms": int((t + len(seg) / SR) * 1000), "text": text})
        parts.append(seg); t += len(seg) / SR
    audio = np.concatenate(parts)
    id_ = f"long_{r:02d}"
    sf.write(out / "audio" / f"{id_}.wav", (np.clip(audio, -1, 1) * 32767).astype("int16"), SR, subtype="PCM_16")
    json.dump({"transcript": utts}, open(out / "ref" / f"{id_}.json", "w", encoding="utf-8"), ensure_ascii=False)
    man.append([id_, f"audio/{id_}.wav", f"ref/{id_}.json", int(len(audio) / SR * 1000), 2, "ru", "fleurs-concat", "clean", "dev"])
with open(out / "manifest.csv", "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f); w.writerow("id,audio,ref,duration_ms,num_speakers,lang,source,condition,split".split(",")); w.writerows(man)
print(len(man), "recordings", sum(m[3] for m in man) / 1000, "s")
