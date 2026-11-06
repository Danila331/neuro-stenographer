"""stenoeval command line interface."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import __version__


def cmd_validate(a: argparse.Namespace) -> int:
    from .validate import validate_file

    bad = 0
    for f in a.files:
        r = validate_file(Path(f), a.duration_ms)
        if r.ok:
            print(f"OK   {f}")
        else:
            bad += 1
            print(f"FAIL {f}")
            for e in r.errors[: a.max_errors]:
                print(f"     - {e}")
            if len(r.errors) > a.max_errors:
                print(f"     ... and {len(r.errors) - a.max_errors} more")
        for w in r.warnings:
            print(f"     warning: {w}")
    return 1 if bad else 0


def cmd_score(a: argparse.Namespace) -> int:
    from .report import legend, summary_table
    from .results import append_rows
    from .score import ScoreOptions, dump_json, run_score

    opt = ScoreOptions(
        dataset_root=Path(a.dataset), run_dir=Path(a.run), run_id=a.run_id, tier=a.tier, platform=a.platform,
        device=a.device, num_threads=a.num_threads, notes=a.notes or "", split=a.split,
        ids=a.ids.split(",") if a.ids else None, only_present=a.only_present, dataset_name=a.dataset_name,
        commit=a.commit,
    )
    if a.csv and not a.replace and Path(a.csv).is_file():
        from .results import read_csv

        rid = a.run_id or Path(a.run).resolve().name
        if any(r.get("run_id") == rid for r in read_csv(Path(a.csv))):
            raise SystemExit(f"run_id {rid!r} already present in {a.csv}; use --replace or another --run-id")
    scores, warnings = run_score(opt)
    for w in warnings:
        print(f"warning: {w}", file=sys.stderr)
    rows = [{k: ("" if v is None else str(v)) for k, v in s.row.items()} for s in scores]
    print(f"run_id={rows[0]['run_id']}  files={len(rows)}  dataset={rows[0]['dataset']}  commit={rows[0]['commit'][:10]}")
    print(summary_table(rows))
    print(legend())
    if a.json_out:
        dump_json(Path(a.json_out), scores)
        print(f"details -> {a.json_out}")
    if a.csv:
        append_rows(Path(a.csv), [s.row for s in scores], replace_run=a.replace)
        print(f"{len(rows)} rows appended to {a.csv}")
    return 0


def cmd_compare(a: argparse.Namespace) -> int:
    from .report import compare_runs
    from .results import read_csv

    text, regress = compare_runs(read_csv(Path(a.csv)), [x.strip() for x in a.run_ids.split(",") if x.strip()], a.tol, a.markdown)
    print(text)
    return 1 if (regress and a.fail_on_regression) else 0


def cmd_report(a: argparse.Namespace) -> int:
    from .report import md_report
    from .results import read_csv

    rows = read_csv(Path(a.csv))
    ids = [x.strip() for x in a.run_ids.split(",")] if a.run_ids else None
    if not rows:
        raise SystemExit(f"{a.csv} has no rows")
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(md_report(rows, ids, detail=not a.summary_only), encoding="utf-8")
    print(f"report -> {out}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="stenoeval", description="Evaluation harness for the Neuro-Stenographer case")
    p.add_argument("--version", action="version", version=f"stenoeval {__version__}")
    sub = p.add_subparsers(dest="cmd", required=True)

    v = sub.add_parser("validate", help="strictly validate output JSON files against the ТЗ contract")
    v.add_argument("files", nargs="+")
    v.add_argument("--duration-ms", type=int, default=None, help="audio duration; also checks end_ms <= duration")
    v.add_argument("--max-errors", type=int, default=20)
    v.set_defaults(fn=cmd_validate)

    s = sub.add_parser("score", help="score a run directory against a dataset")
    s.add_argument("--dataset", required=True, help="dataset root (manifest.csv, ref/)")
    s.add_argument("--run", required=True, help="run dir with <id>.json and <id>.meta.json")
    s.add_argument("--run-id", default=None, help="default: run directory name")
    s.add_argument("--tier", default=None)
    s.add_argument("--platform", choices=["desktop", "android"], default="desktop")
    s.add_argument("--device", default=None)
    s.add_argument("--num-threads", default=None)
    s.add_argument("--notes", default="")
    s.add_argument("--split", choices=["dev", "test", "all"], default=None)
    s.add_argument("--ids", default=None, help="comma-separated file ids")
    s.add_argument("--only-present", action="store_true", help="skip files without hypothesis (default: score as empty)")
    s.add_argument("--dataset-name", default=None)
    s.add_argument("--commit", default=None, help="override git rev-parse HEAD")
    s.add_argument("--csv", default=None, help="append rows to this results.csv (e.g. artifacts/test-results/results.csv)")
    s.add_argument("--replace", action="store_true", help="replace existing rows with the same run_id in --csv")
    s.add_argument("--json-out", default=None, help="write full per-file details (all modes/collars) as JSON")
    s.set_defaults(fn=cmd_score)

    c = sub.add_parser("compare", help="compare runs from results.csv (first run = baseline)")
    c.add_argument("csv")
    c.add_argument("--run-ids", required=True, help="a,b[,c...]")
    c.add_argument("--tol", type=float, default=0.0, help="allowed accuracy worsening, absolute fraction (0.002 = 0.2 pp)")
    c.add_argument("--markdown", action="store_true")
    c.add_argument("--fail-on-regression", action="store_true", help="exit code 1 if accuracy is worse than baseline")
    c.set_defaults(fn=cmd_compare)

    r = sub.add_parser("report", help="markdown tables from results.csv")
    r.add_argument("csv")
    r.add_argument("--out", required=True)
    r.add_argument("--run-ids", default=None)
    r.add_argument("--summary-only", action="store_true")
    r.set_defaults(fn=cmd_report)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return int(args.fn(args) or 0)


if __name__ == "__main__":
    sys.exit(main())
