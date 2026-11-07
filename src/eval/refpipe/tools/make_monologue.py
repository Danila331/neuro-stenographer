"""Build the `ru-monologue` dataset (single-speaker recordings made of 5-10 read phrases of one speaker) from `ru-asr-dev`
(RuLS / VoxForge, speaker id known).  Format = docs/team/ARCHITECTURE_TEAM.md section 2.

    python tools/make_monologue.py --src $ROOT/.cache/data/ru-asr-dev --out $ROOT/.cache/data/ru-monologue

Phrases of ONE recording session are concatenated with pauses of 0.4-1.2 s (seed-fixed low-level noise): for RuLS the session
is (speaker, book chapter) -- the first 4 fields of the id, e.g. ruls_2671_poemi_02 --, for VoxForge the speaker.  (Mixing
chapters of a speaker gave non-stationary channel conditions: a clustering of such "monologues" legitimately splits them.)
A session is split into several files of at most --max-s seconds; sessions with < --min-total-s seconds are skipped.
The reference is one utterance per phrase (times shifted by the phrase offset).  Splits: speakers alternate dev / test.
"""
from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 16000


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--max-s", type=float, default=120.0)
    ap.add_argument("--min-total-s", type=float, default=20.0)
    ap.add_argument("--min-file-s", type=float, default=15.0)
    a = ap.parse_args()
    src, out = Path(a.src), Path(a.out)
    (out / "audio").mkdir(parents=True, exist_ok=True)
    (out / "ref").mkdir(parents=True, exist_ok=True)
    rows = list(csv.DictReader((src / "manifest.csv").open(encoding="utf-8")))
    by_spk = defaultdict(list)
    for r in rows:
        if r["speaker"]:
            sess = r["speaker"] + ("|" + "_".join(r["orig_id"].split("_")[:4]) if r["speaker"].startswith("ruls") else "")
            by_spk[sess].append(r)
    rng = np.random.default_rng(0)
    manifest, k = [], 0
    for si, (spk, items) in enumerate(sorted(by_spk.items())):
        items = sorted(items, key=lambda r: r["id"])
        total = sum(int(r["duration_ms"]) for r in items) / 1000
        if total < a.min_total_s:
            continue
        # greedy split into files <= max_s
        groups, cur, dur = [], [], 0.0
        for r in items:
            d = int(r["duration_ms"]) / 1000 + 0.8
            if cur and dur + d > a.max_s:
                groups.append(cur)
                cur, dur = [], 0.0
            cur.append(r)
            dur += d
        if cur:
            if groups and dur < a.min_file_s:
                groups[-1].extend(cur)
            else:
                groups.append(cur)
        for gi, g in enumerate(groups):
            parts, utts, pos = [], [], 0
            for r in g:
                x, sr = sf.read(src / r["audio"], dtype="float32")
                assert sr == SR
                if x.ndim > 1:
                    x = x.mean(1)
                ref = json.loads((src / r["ref"]).read_text(encoding="utf-8"))["transcript"]
                for u in ref:
                    utts.append({"speaker_id": "speaker_1", "start_ms": pos + u["start_ms"], "end_ms": pos + u["end_ms"], "text": u["text"]})
                parts.append(x)
                pos += len(x) * 1000 // SR
                pause = int(rng.uniform(0.4, 1.2) * SR)
                parts.append((rng.standard_normal(pause) * 10 ** (-62 / 20)).astype(np.float32))
                pos += pause * 1000 // SR
            y = np.concatenate(parts[:-1])
            fid = f"mono_{k:02d}_{spk.split('|')[-1]}"
            sf.write(out / "audio" / f"{fid}.wav", y, SR, subtype="PCM_16")
            dur_ms = len(y) * 1000 // SR
            utts = [u for u in utts if u["end_ms"] <= dur_ms]
            (out / "ref" / f"{fid}.json").write_text(json.dumps({"transcript": utts}, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
            manifest.append({"id": fid, "audio": f"audio/{fid}.wav", "ref": f"ref/{fid}.json", "duration_ms": dur_ms, "num_speakers": 1,
                             "lang": "ru", "source": "ru-asr-dev-concat", "condition": "clean", "split": "dev" if si % 2 == 0 else "test",
                             "scenario": "monologue", "speaker": spk.split("|")[0], "phrases": len(g), "license": g[0]["license"]})
            k += 1
    cols = list(manifest[0])
    with (out / "manifest.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        w.writerows(manifest)
    print(len(manifest), "files,", sum(m["duration_ms"] for m in manifest) / 1000, "s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
