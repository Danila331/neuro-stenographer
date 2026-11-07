"""Parameter sweeps on a dataset split with stage caching.

    python -m refpipe.sweep --config config/tiers/medium.json --dataset DATASET_ROOT --split dev \
        --combos combos.json --out-dir RUNS/sweep1 --cache RUNS/refpipe-cache --csv artifacts/test-results/results.csv \
        [--prefix sweep-] [--extra-override k=v ...] [--language ru]

combos.json: [{"name": "thr030-all", "overrides": ["vad.threshold=0.3", "audio.normalize_gain.enabled=true"]}, ...]
Each combo = `refpipe run` (hypotheses in OUT_DIR/<name>/) + `stenoeval score` (+ rows in results.csv with
run_id=<prefix><name>).  A TSV line with the micro metrics (WER norm S/D/I, WER punct, DER on the final utterances
with collar 0 and NIST, DER components, speaker-count accuracy, RTF) is appended to OUT_DIR/summary.tsv as soon as a
combo finishes.  Finished combos (summary line present) are skipped, so a sweep can be resumed.
"""
from __future__ import annotations

import argparse
import collections
import csv
import json
import subprocess
import sys
from pathlib import Path


def summarize(details: list[dict]) -> dict:
    a = collections.Counter()
    for x in details:
        w = x["wer"]["norm"]
        a["ref"] += w["ref_words"]; a["sub"] += w["sub"]; a["del"] += w["del"]; a["ins"] += w["ins"]
        p = x["wer"]["punct"]
        a["pref"] += p["ref_words"]; a["perr"] += p["sub"] + p["del"] + p["ins"]
        if x.get("der"):
            c = x["der"]["c0"]; n = x["der"]["nist"]
            a["t0"] += c["total_s"]; a["m0"] += c["missed_s"]; a["f0"] += c["false_alarm_s"]; a["c0"] += c["confusion_s"]
            a["tn"] += n["total_s"]; a["en"] += n["missed_s"] + n["false_alarm_s"] + n["confusion_s"]
        a["n"] += 1
        a["spk_ok"] += bool((x.get("speakers") or {}).get("correct"))
        a["dur"] += x["duration_ms"] / 1000
        a["proc"] += float(x["row"].get("processing_ms") or 0) / 1000
    pc = lambda v, t: round(100 * v / t, 2) if t else None  # noqa: E731
    return {
        "files": a["n"], "wer_norm": pc(a["sub"] + a["del"] + a["ins"], a["ref"]), "sub": pc(a["sub"], a["ref"]),
        "del": pc(a["del"], a["ref"]), "ins": pc(a["ins"], a["ref"]), "wer_punct": pc(a["perr"], a["pref"]),
        "der_c0": pc(a["m0"] + a["f0"] + a["c0"], a["t0"]), "der_nist": pc(a["en"], a["tn"]),
        "miss": pc(a["m0"], a["t0"]), "fa": pc(a["f0"], a["t0"]), "conf": pc(a["c0"], a["t0"]),
        "spk_ok": f"{a['spk_ok']}/{a['n']}", "rtf": round(a["proc"] / a["dur"], 4) if a["dur"] else None,
    }


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", required=True)
    ap.add_argument("--dataset", required=True)
    ap.add_argument("--split", default="dev")
    ap.add_argument("--combos", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--cache", default=None)
    ap.add_argument("--csv", default=None, help="append rows to this results.csv (run_id=<prefix><name>)")
    ap.add_argument("--prefix", default="sweep-")
    ap.add_argument("--extra-override", action="append", default=[])
    ap.add_argument("--models", default=None)
    ap.add_argument("--language", default=None)
    ap.add_argument("--num-threads", type=int, default=None)
    ap.add_argument("--only", nargs="*", default=None, help="run only these combo names")
    a = ap.parse_args(argv)
    out = Path(a.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    summary = out / "summary.tsv"
    done = set()
    if summary.is_file():
        done = {line.split("\t", 1)[0] for line in summary.read_text().splitlines()[1:]}
    combos = json.loads(Path(a.combos).read_text())
    for c in combos:
        name = c["name"]
        if name in done or (a.only and name not in a.only):
            continue
        run_dir = out / name
        cmd = [sys.executable, "-m", "refpipe.cli", "run", "--config", a.config, "--input", a.dataset,
               "--split", a.split, "--out", str(run_dir)]
        if a.models:
            cmd += ["--models", a.models]
        if a.cache:
            cmd += ["--cache", a.cache]
        if a.language:
            cmd += ["--language", a.language]
        if a.num_threads:
            cmd += ["--num-threads", str(a.num_threads)]
        for o in a.extra_override + c.get("overrides", []):
            cmd += ["--override", o]
        print("RUN", name, flush=True)
        with (out / f"{name}.log").open("w") as lg:
            rc = subprocess.run(cmd, stdout=lg, stderr=subprocess.STDOUT).returncode
        if rc != 0:
            print(f"  refpipe failed rc={rc}, see {name}.log", flush=True)
            continue
        js = out / f"{name}.details.json"
        sc = [sys.executable, "-m", "stenoeval.cli", "score", "--dataset", a.dataset, "--run", str(run_dir),
              "--split", a.split, "--run-id", a.prefix + name, "--json-out", str(js),
              "--notes", "sweep: " + " ".join(a.extra_override + c.get("overrides", []))]
        if a.csv:
            sc += ["--csv", a.csv, "--replace"]
        subprocess.run(sc, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if not js.is_file():
            print("  scoring failed", flush=True)
            continue
        s = summarize(json.loads(js.read_text()))
        write_header = not summary.is_file()
        with summary.open("a") as f:
            if write_header:
                f.write("name\t" + "\t".join(s) + "\n")
            f.write(name + "\t" + "\t".join(str(v) for v in s.values()) + "\n")
        print("  ", name, s, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
