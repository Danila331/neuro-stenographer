#!/usr/bin/env python3
"""Parallel ranged download from huggingface.co (public, no token) into the shared model cache, atomically.
usage: pfetch.py <name> <repo> <file> [connections]"""
import os, sys, shutil, threading, time
from pathlib import Path
import requests

name, repo, file = sys.argv[1:4]
conns = int(sys.argv[4]) if len(sys.argv) > 4 else 8
root = Path(os.environ.get("ROOT", "/Users/danilka2203/Desktop/work/neuro-stenographer-claude"))
dst = root / ".cache/models" / f"llm-{name}"
if (dst / file).exists():
    print("have", name); sys.exit(0)
tmp = root / ".cache/models" / f".tmp-llm-bench-{name}"
tmp.mkdir(parents=True, exist_ok=True)
out = tmp / (file + ".pf")
url = f"https://huggingface.co/{repo}/resolve/main/{file}"
r = requests.head(url, allow_redirects=True, timeout=30)
size = int(r.headers["Content-Length"]); final = r.url
chunk = 16 * 2**20
n = (size + chunk - 1) // chunk
done = set()
state = tmp / (file + ".state")
if state.exists() and out.exists():
    done = {int(x) for x in state.read_text().split()}
else:
    with open(out, "wb") as f:
        f.truncate(size)
lock = threading.Lock()
todo = [i for i in range(n) if i not in done]
got = [len(done) * chunk]

def worker():
    s = requests.Session()
    while True:
        with lock:
            if not todo:
                return
            i = todo.pop(0)
        a, b = i * chunk, min(size, (i + 1) * chunk) - 1
        for attempt in range(30):
            try:
                resp = s.get(final, headers={"Range": f"bytes={a}-{b}"}, timeout=60)
                if resp.status_code != 206 or len(resp.content) != b - a + 1:
                    raise IOError(resp.status_code)
                with lock:
                    with open(out, "r+b") as f:
                        f.seek(a); f.write(resp.content)
                    done.add(i); state.write_text(" ".join(map(str, sorted(done))))
                break
            except Exception as e:
                time.sleep(2)
        else:
            raise SystemExit(f"chunk {i} failed")

ths = [threading.Thread(target=worker) for _ in range(conns)]
[t.start() for t in ths]
t0 = time.time()
while any(t.is_alive() for t in ths):
    time.sleep(10)
    print(f"{name}: {len(done)}/{n} chunks, {len(done)*chunk/1e6/(time.time()-t0+1e-9):.1f} MB/s avg", flush=True)
if len(done) != n:
    raise SystemExit("incomplete")
(tmp / file).unlink(missing_ok=True)
out.rename(tmp / file); state.unlink(missing_ok=True)
tmp.rename(dst)
print("done", name)
