"""results.csv I/O, machine-readable counters in the ``notes`` column, aggregation."""

from __future__ import annotations

import csv
import fcntl
import re
from pathlib import Path
from typing import Iterable, Sequence

COLUMNS = [
    "run_id", "timestamp_utc", "commit", "tier", "config_hash", "platform", "device", "num_threads",
    "dataset", "file_id", "condition", "duration_ms", "num_speakers_ref", "num_speakers_hyp",
    "wer_raw", "wer_norm", "cpwer_norm", "der", "der_collar", "jer", "ts_start_mae_ms",
    "ts_end_mae_ms", "processing_ms", "rtf", "notes",
]

# `notes` = "<free text> [k=v k=v ...]"; the bracket part carries the raw counters needed to
# aggregate exactly (micro-averaged WER etc.) from the CSV alone.
_TAIL_RE = re.compile(r"\s*\[((?:[A-Za-z_0-9]+=[^\s\]]*\s*)+)\]\s*$")


def pack_notes(free: str, counters: dict[str, object]) -> str:
    parts = []
    for k, v in counters.items():
        if v is None:
            continue
        parts.append(f"{k}={v:.4g}" if isinstance(v, float) else f"{k}={v}")
    tail = "[" + " ".join(parts) + "]" if parts else ""
    return " ".join(x for x in (free.strip(), tail) if x)


def unpack_notes(notes: str) -> tuple[str, dict[str, str]]:
    m = _TAIL_RE.search(notes or "")
    if not m:
        return (notes or "").strip(), {}
    kv = dict(p.split("=", 1) for p in m.group(1).split())
    return notes[: m.start()].strip(), kv


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def append_rows(path: Path, rows: Sequence[dict[str, object]], replace_run: bool = False) -> None:
    """Append rows (header is created if the file is new/empty). Header must match COLUMNS."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+", newline="", encoding="utf-8") as f:
        fcntl.flock(f, fcntl.LOCK_EX)
        try:
            f.seek(0)
            content = f.read()
            existing: list[dict[str, str]] = []
            if content.strip():
                reader = csv.DictReader(content.splitlines())
                if reader.fieldnames != COLUMNS:
                    raise SystemExit(f"{path}: header differs from the standard columns: {reader.fieldnames}")
                existing = list(reader)
            run_ids = {r["run_id"] for r in rows}
            clash = run_ids & {r["run_id"] for r in existing}
            if clash and not replace_run:
                raise SystemExit(f"run_id already present in {path}: {sorted(clash)} (use --replace or another --run-id)")
            if clash:
                keep = [r for r in existing if r["run_id"] not in clash]
                f.seek(0)
                f.truncate()
                w = csv.DictWriter(f, fieldnames=COLUMNS, lineterminator="\n")
                w.writeheader()
                w.writerows(keep)
            elif not content.strip():
                csv.DictWriter(f, fieldnames=COLUMNS, lineterminator="\n").writeheader()
            elif not content.endswith("\n"):
                f.write("\n")
            f.seek(0, 2)
            csv.DictWriter(f, fieldnames=COLUMNS, lineterminator="\n").writerows(rows)
        finally:
            fcntl.flock(f, fcntl.LOCK_UN)


def fnum(v: object) -> float | None:
    try:
        if v is None or v == "":
            return None
        x = float(v)  # type: ignore[arg-type]
        return None if x != x else x
    except (TypeError, ValueError):
        return None


def mean(xs: Iterable[float | None]) -> float | None:
    v = [x for x in xs if x is not None]
    return sum(v) / len(v) if v else None


def _micro(rows: Sequence[dict[str, str]], err: str, tot: str, fallback: str | None) -> tuple[float | None, bool]:
    """Sum(err)/Sum(tot) from the notes counters; falls back to the plain mean of a column."""
    e = t = 0.0
    ok = True
    for r in rows:
        kv = unpack_notes(r.get("notes", ""))[1]
        if err in kv and tot in kv:
            e += float(kv[err])
            t += float(kv[tot])
        else:
            ok = False
    if ok and rows:
        return (e / max(t, 1.0)), True
    if fallback is None:
        return None, False
    return mean(fnum(r.get(fallback)) for r in rows), False


def summarize(rows: Sequence[dict[str, str]]) -> dict[str, object]:
    """Aggregate the rows of ONE run (or one condition of it)."""
    n = len(rows)
    out: dict[str, object] = {"files": n}
    out["wer_raw"], out["wer_raw_exact"] = _micro(rows, "werr_raw", "refw_raw", "wer_raw")
    out["wer_norm"], out["wer_norm_exact"] = _micro(rows, "werr", "refw", "wer_norm")
    out["wer_num"], _ = _micro(rows, "werr_num", "refw_num", None)
    out["wer_hy"], _ = _micro(rows, "werr_hy", "refw_hy", None)
    out["cpwer_norm"], out["cpwer_exact"] = _micro(rows, "cperr", "refw", "cpwer_norm")
    out["wer_norm_macro"] = mean(fnum(r.get("wer_norm")) for r in rows)
    out["der"], out["der_exact"] = _micro(rows, "derr", "dtot", "der")
    out["der_collar"], _ = _micro(rows, "derr_c", "dtot_c", "der_collar")
    out["jer"] = mean(fnum(r.get("jer")) for r in rows)
    sp = [(fnum(r.get("num_speakers_ref")), fnum(r.get("num_speakers_hyp"))) for r in rows]
    sp = [(a, b) for a, b in sp if a is not None and b is not None]
    out["spk_acc"] = sum(a == b for a, b in sp) / len(sp) if sp else None
    out["spk_mae"] = sum(abs(a - b) for a, b in sp) / len(sp) if sp else None
    for col, key in (("ts_start_mae_ms", "tsn"), ("ts_end_mae_ms", "tsn")):
        num = den = 0.0
        for r in rows:
            v = fnum(r.get(col))
            if v is None:
                continue
            w = fnum(unpack_notes(r.get("notes", ""))[1].get(key)) or 1.0
            num += v * w
            den += w
        out[col] = num / den if den else None
    acc = [fnum(unpack_notes(r.get("notes", ""))[1].get("ts_acc300")) for r in rows]
    out["ts_acc300"] = mean(acc)
    out["rtf_mean"] = mean(fnum(r.get("rtf")) for r in rows)
    proc = [(fnum(r.get("processing_ms")), fnum(r.get("duration_ms"))) for r in rows]
    proc = [(p, d) for p, d in proc if p is not None and d]
    out["rtf_total"] = sum(p for p, _ in proc) / sum(d for _, d in proc) if proc else None
    out["dur_s"] = sum(fnum(r.get("duration_ms")) or 0.0 for r in rows) / 1000.0
    return out


def by_condition(rows: Sequence[dict[str, str]]) -> dict[str, list[dict[str, str]]]:
    d: dict[str, list[dict[str, str]]] = {}
    for r in rows:
        d.setdefault(r.get("condition") or "?", []).append(r)
    return dict(sorted(d.items()))


def group_runs(rows: Sequence[dict[str, str]]) -> dict[str, list[dict[str, str]]]:
    d: dict[str, list[dict[str, str]]] = {}
    for r in rows:
        d.setdefault(r["run_id"], []).append(r)
    return d


def fmt_pct(v: object, digits: int = 2) -> str:
    return "-" if v is None else f"{100 * float(v):.{digits}f}"  # type: ignore[arg-type]


def fmt_num(v: object, digits: int = 0) -> str:
    return "-" if v is None else f"{float(v):.{digits}f}"  # type: ignore[arg-type]
