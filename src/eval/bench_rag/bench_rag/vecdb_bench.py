"""Vector store micro-benchmark on the PC (relative numbers; the phone is ~3-5x slower).

python -m bench_rag.vecdb_bench --n 10000 100000 --dim 384 [--backends numpy sqlitevec usearch objectbox]
Each backend runs in its own process (fresh peak RSS). Data: clustered unit vectors (synthetic; real chat embeddings are also clustered).
"""
from __future__ import annotations

import argparse
import json
import os
import resource
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import numpy as np


def make_data(n: int, dim: int, q: int = 200, seed: int = 0):
    rng = np.random.default_rng(seed)
    centers = rng.normal(size=(max(10, n // 100), dim)).astype(np.float32)
    x = centers[rng.integers(0, len(centers), n)] + 0.7 * rng.normal(size=(n, dim)).astype(np.float32)
    x /= np.linalg.norm(x, axis=1, keepdims=True)
    qi = rng.integers(0, n, q)
    qs = x[qi] + 0.3 * rng.normal(size=(q, dim)).astype(np.float32) / np.sqrt(dim)
    qs /= np.linalg.norm(qs, axis=1, keepdims=True)
    return x.astype(np.float32), qs.astype(np.float32)


def truth(x, qs, k=10):
    return [set(np.argsort(-(x @ q))[:k]) for q in qs]


def rss_mb() -> float:
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 2**20     # macOS: bytes


def timeit(fn, qs, warm=5):
    for q in qs[:warm]:
        fn(q)
    ts = []
    for q in qs:
        t = time.perf_counter()
        fn(q)
        ts.append((time.perf_counter() - t) * 1000)
    return float(np.percentile(ts, 50)), float(np.percentile(ts, 95))


def run(backend: str, n: int, dim: int):
    x, qs = make_data(n, dim)
    gt = truth(x, qs)
    base = rss_mb()
    out = {"backend": backend, "n": n, "dim": dim}
    k = 10
    tmp = Path(tempfile.mkdtemp())
    if backend == "numpy-f32":                     # brute force in RAM (what Room + float BLOB would do after loading)
        t = time.perf_counter(); m = x.copy(); out["build_s"] = time.perf_counter() - t
        fn = lambda q: np.argpartition(-(m @ q), k)[:k]
        res = [set(fn(q)) for q in qs]; out["size_mb"] = m.nbytes / 2**20
    elif backend == "numpy-i8":                    # int8 scalar quantisation, brute force
        t = time.perf_counter(); m8 = np.round(x * 127).astype(np.int8); out["build_s"] = time.perf_counter() - t
        fn = lambda q: np.argpartition(-(m8.astype(np.float32) @ q), k)[:k]
        res = [set(fn(q)) for q in qs]; out["size_mb"] = m8.nbytes / 2**20
    elif backend == "sqlitevec":
        import sqlite3
        import sqlite_vec
        db = sqlite3.connect(str(tmp / "v.db")); db.enable_load_extension(True); sqlite_vec.load(db); db.enable_load_extension(False)
        db.execute(f"create virtual table v using vec0(e float[{dim}] distance_metric=cosine)")
        t = time.perf_counter()
        with db:
            db.executemany("insert into v(rowid, e) values (?, ?)", ((i, sqlite_vec.serialize_float32(x[i].tolist())) for i in range(n)))
        out["build_s"] = time.perf_counter() - t
        sq = [sqlite_vec.serialize_float32(q.tolist()) for q in qs]
        idx = {q.tobytes(): s for q, s in zip(qs, sq)}
        def fn(q): return db.execute("select rowid from v where e match ? and k = 10", (idx[q.tobytes()],)).fetchall()
        res = [set(r[0] for r in fn(q)) for q in qs]
        out["size_mb"] = (tmp / "v.db").stat().st_size / 2**20
    elif backend == "sqlitevec-i8":
        import sqlite3
        import sqlite_vec
        db = sqlite3.connect(str(tmp / "v.db")); db.enable_load_extension(True); sqlite_vec.load(db); db.enable_load_extension(False)
        db.execute(f"create virtual table v using vec0(e int8[{dim}] distance_metric=cosine)")
        t = time.perf_counter()
        x8 = np.round(x * 127).astype(np.int8)
        with db:
            db.executemany("insert into v(rowid, e) values (?, vec_int8(?))", ((i, x8[i].tobytes()) for i in range(n)))
        out["build_s"] = time.perf_counter() - t
        q8 = {q.tobytes(): np.round(q * 127).astype(np.int8).tobytes() for q in qs}
        def fn(q): return db.execute("select rowid from v where e match vec_int8(?) and k = 10", (q8[q.tobytes()],)).fetchall()
        res = [set(r[0] for r in fn(q)) for q in qs]
        out["size_mb"] = (tmp / "v.db").stat().st_size / 2**20
    elif backend.startswith("usearch"):
        from usearch.index import Index
        dtype = backend.split("-")[1] if "-" in backend else "f32"
        ix = Index(ndim=dim, metric="cos", dtype=dtype, connectivity=16, expansion_add=128, expansion_search=64)
        t = time.perf_counter(); ix.add(np.arange(n), x, threads=1); out["build_s"] = time.perf_counter() - t
        fn = lambda q: ix.search(q, k).keys
        res = [set(fn(q).tolist()) for q in qs]
        ix.save(str(tmp / "i.usearch")); out["size_mb"] = (tmp / "i.usearch").stat().st_size / 2**20
    elif backend == "objectbox":
        import objectbox
        from objectbox.model import Entity, Float32Vector, HnswIndex, Id, Model
        from objectbox.model.properties import VectorDistanceType

        @Entity()
        class Chunk:
            id = Id()
            vec = Float32Vector(index=HnswIndex(dimensions=dim, distance_type=VectorDistanceType.COSINE, neighbors_per_node=16, indexing_search_count=128))
        model = Model(); model.entity(Chunk)
        ob = objectbox.Builder().model(model).directory(str(tmp / "ob")).build()
        box = ob.box(Chunk)
        t = time.perf_counter()
        for s in range(0, n, 5000):
            box.put(*[Chunk(vec=x[i]) for i in range(s, min(n, s + 5000))])
        out["build_s"] = time.perf_counter() - t
        qb = lambda q: box.query(Chunk.vec.nearest_neighbor(q, k)).build()
        def fn(q): return qb(q).find_ids()
        res = [set(i - 1 for i in fn(q)) for q in qs]
        out["size_mb"] = sum(f.stat().st_size for f in (tmp / "ob").rglob("*") if f.is_file()) / 2**20
    else:
        raise SystemExit(backend)
    if backend.startswith("sqlitevec") or backend == "objectbox" or backend.startswith("usearch"):
        pass
    p50, p95 = timeit(fn, qs)
    out.update(recall10=round(float(np.mean([len(a & b) / k for a, b in zip(res, gt)])), 3), q_p50_ms=round(p50, 2), q_p95_ms=round(p95, 2),
               build_s=round(out["build_s"], 1), size_mb=round(out["size_mb"], 1), rss_delta_mb=round(rss_mb() - base, 0))
    print(json.dumps(out), flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, nargs="+", default=[10000, 100000])
    ap.add_argument("--dim", type=int, default=384)
    ap.add_argument("--backends", nargs="+", default=["numpy-f32", "numpy-i8", "sqlitevec", "sqlitevec-i8", "usearch-f32", "usearch-f16", "usearch-i8", "objectbox"])
    ap.add_argument("--one", default="")
    a = ap.parse_args()
    if a.one:
        run(a.one, a.n[0], a.dim)
        return
    env = {**os.environ, "OMP_NUM_THREADS": "1", "VECLIB_MAXIMUM_THREADS": "1", "OPENBLAS_NUM_THREADS": "1"}
    for n in a.n:
        for b in a.backends:
            r = subprocess.run([sys.executable, "-m", "bench_rag.vecdb_bench", "--one", b, "--n", str(n), "--dim", str(a.dim)], env=env, capture_output=True, text=True)
            print(r.stdout.strip() or f"FAIL {b} {n}: {r.stderr[-400:]}", flush=True)


if __name__ == "__main__":
    main()
