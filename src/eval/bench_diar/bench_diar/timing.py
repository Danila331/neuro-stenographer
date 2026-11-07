"""Timing / peak-RSS of the diarization stages in a fresh process (run when the machine is quiet).

python -m bench_diar.timing diar <emb> <shift> <seconds> [threads]
python -m bench_diar.timing units <emb> <seconds> [threads]      # phrase-level: unit embeddings only
"""
import resource
import sys
import time

import numpy as np

from .data import load_audio
from .e2e import load_cases


def long_audio(seconds: float):
    cases = [c for c in load_cases(["ru-dialog-synth"], "dev")]
    xs, ws, off = [], [], 0
    for c in cases:
        x = load_audio(c.audio)
        xs.append(x)
        ws += [(w.start_ms + off, w.end_ms + off) for w in c.words]
        off += len(x) * 1000 // 16000
        if off / 1000 >= seconds:
            break
    x = np.concatenate(xs)[: int(seconds * 16000)]
    ws = [w for w in ws if w[1] <= seconds * 1000]
    return x, ws


def rss_mb():
    r = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return r / 1e6 if sys.platform == "darwin" else r / 1e3


def main():
    kind = sys.argv[1]
    if kind == "diar":
        emb, shift, sec = sys.argv[2], float(sys.argv[3]), float(sys.argv[4])
        th = int(sys.argv[5]) if len(sys.argv) > 5 else 4
        x, _ = long_audio(sec)
        from .runner import Cfg, build, run_one
        sd = build(Cfg(emb=emb, threshold=0.5, shift=shift, threads=th, min_on=0.2, min_off=0.3))
        base = rss_mb()
        segs, el, cpu = run_one(sd, x)
        print(f"diar emb={emb[-28:]} shift={shift} audio={len(x) / 16000:.0f}s threads={th}: wall={el:.1f}s RTF={el / (len(x) / 16000):.3f} "
              f"cpu={cpu:.1f}s maxRSS={rss_mb():.0f}MB (after load {base:.0f}MB)")
    else:
        emb, sec = sys.argv[2], float(sys.argv[3])
        th = int(sys.argv[4]) if len(sys.argv) > 4 else 4
        x, ws = long_audio(sec)
        from .phrase import Extractor
        from .spkphrase import Params, embed_units, label_units, make_units
        ex = Extractor(emb, th)
        base = rss_mb()
        p = Params(gap_ms=200)
        t = time.perf_counter()
        units = make_units(ws, None, p)
        E = embed_units(x, units, ex, p)
        t1 = time.perf_counter()
        lab = label_units(E, np.array([u[1] - u[0] for u in units]), p)
        t2 = time.perf_counter()
        print(f"units emb={emb[-28:]} audio={len(x) / 16000:.0f}s threads={th}: units={len(units)} embed={t1 - t:.1f}s "
              f"(RTF {(t1 - t) / (len(x) / 16000):.3f}) cluster={t2 - t1:.2f}s maxRSS={rss_mb():.0f}MB (after load {base:.0f}MB)")


main()
