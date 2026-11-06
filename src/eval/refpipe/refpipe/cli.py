"""CLI:  refpipe run --config tier.json --models DIR --input FILE|DATASET --out RUN_DIR [...]"""
from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

from .config import apply_overrides, config_hash, load_config
from .dataset import load_manifest
from .export import write_outputs


def default_models_root() -> Path:
    here = Path(__file__).resolve()
    for p in here.parents:
        if p.name == ".worktrees":
            return p.parent / ".cache" / "models"
    return Path.cwd() / ".cache" / "models"


def cmd_run(a) -> int:
    from .pipeline import Pipeline

    cfg = apply_overrides(load_config(a.config), a.override)
    if a.num_threads:
        cfg["num_threads"] = a.num_threads
    models = Path(a.models)
    inp = Path(a.input)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    if inp.is_dir() and (inp / "manifest.csv").is_file():
        items = [(i.id, i.audio, i.num_speakers if a.oracle_num_speakers else a.num_speakers)
                 for i in load_manifest(inp, a.split, a.ids)]
    elif inp.is_dir():
        exts = {".wav", ".flac", ".ogg", ".mp3", ".m4a", ".opus", ".aac"}
        items = [(p.stem, p, a.num_speakers) for p in sorted(inp.iterdir()) if p.suffix.lower() in exts]
    else:
        items = [(inp.stem, inp, a.num_speakers)]
    if not items:
        print("no input files", file=sys.stderr)
        return 2
    pipe = Pipeline(cfg, models)
    (out / "config.json").write_text(json.dumps(cfg, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"tier={cfg['tier']} config_hash={config_hash(cfg)[:12]} threads={cfg['num_threads']} "
          f"load={pipe.load_ms}ms files={len(items)}", flush=True)
    failed = 0
    for fid, path, ns in items:
        try:
            res = pipe.process_file(path, ns)
        except Exception as e:  # noqa: BLE001 -- a failing file must not stop a dataset run
            failed += 1
            logging.exception("failed: %s", fid)
            print(f"[FAIL] {fid}: {e}", file=sys.stderr)
            continue
        write_outputs(out, fid, res, pipe.meta(res))
        sp = len({u.speaker for u in res.utterances})
        print(f"{fid}: {res.duration_ms / 1000:.1f}s -> {len(res.utterances)} utt, {sp} spk, "
              f"{res.processing_ms} ms (RTF {res.processing_ms / max(1, res.duration_ms):.3f}) {res.stage_ms}", flush=True)
    return 1 if failed else 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="refpipe", description="Python reference pipeline of Neuro-Stenographer")
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run", help="transcribe a file / folder / dataset")
    r.add_argument("--config", required=True, help="tier json (config/tiers/medium.json)")
    r.add_argument("--models", default=str(default_models_root()), help="models root (default: shared .cache/models)")
    r.add_argument("--input", required=True, help="audio file, folder, or dataset root with manifest.csv")
    r.add_argument("--out", required=True, help="run dir: <id>.json + <id>.meta.json (+ .full.json)")
    r.add_argument("--split", default=None, help="manifest split to process (dev|test)")
    r.add_argument("--ids", nargs="*", default=None, help="only these manifest ids")
    r.add_argument("--num-speakers", type=int, default=None, help="force the number of speakers")
    r.add_argument("--oracle-num-speakers", action="store_true", help="use num_speakers from the manifest")
    r.add_argument("--num-threads", type=int, default=None, help="override config num_threads")
    r.add_argument("--override", action="append", default=[], metavar="KEY=VALUE",
                   help="override config value, e.g. diarization.clustering.threshold=0.7 (repeatable)")
    r.add_argument("-v", "--verbose", action="store_true")
    r.set_defaults(fn=cmd_run)
    a = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO if a.verbose else logging.WARNING, format="%(levelname)s %(message)s")
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
