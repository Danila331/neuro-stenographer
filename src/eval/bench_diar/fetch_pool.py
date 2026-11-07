"""Fetch a small pool of single-speaker Russian clips (RuLS, CC-BY-4.0, HF istupakov/russian_librispeech).

Reads parquet row-groups through HfFileSystem (one row-group ~ 100 clips of ONE reader, ~21 MB),
keeps the first N good clips per reader -> <out>/<reader>/<k>.wav (16 kHz mono PCM16).
usage: fetch_pool.py OUT_DIR [n_readers=28] [clips=12]
"""
import io
import random
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pyarrow.parquet as pq
import soundfile as sf
from huggingface_hub import HfFileSystem

REPO = "datasets/istupakov/russian_librispeech/data/train-{i:05d}-of-00024.parquet"
out = Path(sys.argv[1])
n_readers = int(sys.argv[2]) if len(sys.argv) > 2 else 28
clips = int(sys.argv[3]) if len(sys.argv) > 3 else 12
fs = HfFileSystem()


def open_pf(i):
    f = fs.open(REPO.format(i=i), "rb", block_size=2 << 20)
    return pq.ParquetFile(f, buffer_size=2 << 20, pre_buffer=False)


def scan(i):
    pf = open_pf(i)
    res = []
    for g in range(pf.metadata.num_row_groups):
        t = pf.read_row_group(g, columns=["audio_filepath"]).column(0).to_pylist()
        res.append((i, g, sorted({p.split("/")[1] for p in t})))
    return res


with ThreadPoolExecutor(4) as ex:
    groups = [g for r in ex.map(scan, range(24)) for g in r]
by_reader = {}
for i, g, rs in groups:
    if len(rs) == 1:
        by_reader.setdefault(rs[0], []).append((i, g))
print(len(by_reader), "readers with pure row-groups", flush=True)
rng = random.Random(7)
readers = sorted(by_reader)
rng.shuffle(readers)
chosen = readers[:n_readers]


def fetch(r):
    d = out / r
    if len(list(d.glob("*.wav"))) >= clips:
        return r, "cached"
    i, g = rng.choice(by_reader[r])
    pf = open_pf(i)
    t = pf.read_row_group(g, columns=["audio", "audio_filepath", "duration", "score"]).to_pylist()
    t = [x for x in t if 8 <= x["duration"] <= 20 and x["score"] > -1.2]
    rng2 = random.Random(r)
    rng2.shuffle(t)
    d.mkdir(parents=True, exist_ok=True)
    k = 0
    for x in t:
        a, sr = sf.read(io.BytesIO(x["audio"]["bytes"]), dtype="float32")
        if sr != 16000 or a.ndim != 1:
            continue
        sf.write(d / f"{k:02d}.wav", a, 16000, subtype="PCM_16")
        k += 1
        if k >= clips:
            break
    return r, k


with ThreadPoolExecutor(3) as ex:
    for r, k in ex.map(fetch, chosen):
        print(r, k, flush=True)
