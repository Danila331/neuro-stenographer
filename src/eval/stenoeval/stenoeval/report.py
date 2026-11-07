"""Text/markdown tables, run comparison."""

from __future__ import annotations

from typing import Sequence

from .results import by_condition, fmt_num, fmt_pct, group_runs, summarize, unpack_notes

# (header, key, formatter)
SUMMARY_COLS = [
    ("files", "files", lambda v: str(v)),
    ("WER raw", "wer_raw", fmt_pct),
    ("WER norm", "wer_norm", fmt_pct),
    ("WER punct", "wer_punct", fmt_pct),
    ("WER p-sep", "wer_punct_sep", fmt_pct),
    ("WER p-lc", "wer_punct_lc", fmt_pct),
    ("WER num", "wer_num", fmt_pct),
    ("cpWER", "cpwer_norm", fmt_pct),
    ("DER", "der", fmt_pct),
    ("DER ±.25", "der_collar", fmt_pct),
    ("JER", "jer", fmt_pct),
    ("spk acc", "spk_acc", fmt_pct),
    ("ts start MAE ms", "ts_start_mae_ms", lambda v: fmt_num(v, 0)),
    ("ts end MAE ms", "ts_end_mae_ms", lambda v: fmt_num(v, 0)),
    ("start<300ms", "ts_acc300", fmt_pct),
    ("RTF", "rtf_total", lambda v: "-" if v is None else f"{v:.3f}"),
]


def _table(header: Sequence[str], rows: Sequence[Sequence[str]], markdown: bool) -> str:
    if markdown:
        lines = ["| " + " | ".join(header) + " |", "|" + "|".join("---" for _ in header) + "|"]
        lines += ["| " + " | ".join(r) + " |" for r in rows]
        return "\n".join(lines)
    widths = [max(len(h), *(len(r[i]) for r in rows)) if rows else len(h) for i, h in enumerate(header)]
    fmt = lambda r: "  ".join(c.ljust(widths[i]) if i == 0 else c.rjust(widths[i]) for i, c in enumerate(r))
    return "\n".join([fmt(header), "  ".join("-" * w for w in widths)] + [fmt(r) for r in rows])


def summary_table(rows: Sequence[dict[str, str]], markdown: bool = False, with_conditions: bool = True) -> str:
    """Rows of ONE run -> table 'all' + one line per condition."""
    groups: list[tuple[str, Sequence[dict[str, str]]]] = [("ALL", rows)]
    if with_conditions:
        groups += list(by_condition(rows).items())
    body = []
    for name, g in groups:
        s = summarize(g)
        body.append([name] + [f(s.get(k)) for _, k, f in SUMMARY_COLS])
    return _table(["group"] + [h for h, _, _ in SUMMARY_COLS], body, markdown)


def legend() -> str:
    return (
        "All error rates in %. WER/cpWER/DER are micro-averaged (sum of errors / sum of reference words or "
        "speech seconds); JER, ts MAE, spk acc are per-file means; RTF = sum(processing_ms)/sum(duration_ms). "
        "Main WER metrics: WER norm (no punctuation) and WER punct (punctuation and case counted, е=ё). WER raw/norm/punct/p-sep/p-lc/num = normalisation modes; DER ±.25 = NIST collar +-0.25 s around reference boundaries (pyannote collar=0.5)."
    )


# ------------------------------------------------------------------ compare

COMPARE_METRICS = [
    ("WER norm", "wer_norm", True),
    ("WER raw", "wer_raw", True),
    ("WER punct", "wer_punct", True),
    ("WER p-sep", "wer_punct_sep", True),
    ("WER p-lc", "wer_punct_lc", True),
    ("WER num", "wer_num", True),
    ("cpWER", "cpwer_norm", True),
    ("DER", "der", True),
    ("DER ±.25", "der_collar", True),
    ("JER", "jer", True),
    ("ts start MAE", "ts_start_mae_ms", True),
    ("ts end MAE", "ts_end_mae_ms", True),
    ("spk acc", "spk_acc", False),   # higher is better
    ("RTF", "rtf_total", True),
]


def compare_runs(
    rows: Sequence[dict[str, str]],
    run_ids: Sequence[str],
    tol: float = 0.0,
    markdown: bool = False,
) -> tuple[str, bool]:
    """Compare runs; the first id is the baseline. Only files present in ALL runs are compared.

    Returns (text, regression) where regression is True if any accuracy metric (WER norm/punct/raw,
    cpWER, DER) of a later run is worse than the baseline by more than ``tol`` (absolute, in
    fraction units, e.g. 0.002 = 0.2 pp).
    """
    runs = group_runs(rows)
    missing = [r for r in run_ids if r not in runs]
    if missing:
        raise SystemExit(f"run ids not found in CSV: {missing}; available: {sorted(runs)}")
    common = None
    for r in run_ids:
        ids = {(x["dataset"], x["file_id"]) for x in runs[r]}
        common = ids if common is None else common & ids
    assert common is not None
    lines: list[str] = []
    for r in run_ids:
        n_all = len(runs[r])
        if n_all != len(common):
            lines.append(f"note: run {r} has {n_all} files, {len(common)} common files compared")
    if not common:
        raise SystemExit("runs have no common files")
    sub = {r: [x for x in runs[r] if (x["dataset"], x["file_id"]) in common] for r in run_ids}
    sums = {r: summarize(sub[r]) for r in run_ids}
    approx = [r for r in run_ids if not sums[r].get("wer_norm_exact")]
    if approx:
        lines.append(f"note: runs {approx} lack exact word counters in notes; WER is the mean of per-file WER")

    header = ["metric"] + list(run_ids) + [f"Δ {run_ids[i]} vs {run_ids[0]}" for i in range(1, len(run_ids))]
    body = []
    regress = False
    accuracy_keys = {"wer_norm", "wer_punct", "wer_raw", "cpwer_norm", "der"}
    for title, key, lower_better in COMPARE_METRICS:
        vals = [sums[r].get(key) for r in run_ids]
        is_pct = key not in ("ts_start_mae_ms", "ts_end_mae_ms", "rtf_total")
        f = fmt_pct if is_pct else ((lambda v: fmt_num(v, 0)) if key.startswith("ts_") else (lambda v: "-" if v is None else f"{v:.3f}"))
        cells = [f(v) for v in vals]
        for i in range(1, len(run_ids)):
            a, b = vals[0], vals[i]
            if a is None or b is None:
                cells.append("-")
                continue
            d = b - a
            worse = d > tol if lower_better else d < -tol
            if worse and key in accuracy_keys:
                regress = True
            ds = f"{100 * d:+.2f} pp" if is_pct else (f"{d:+.0f} ms" if key.startswith("ts_") else f"{d:+.3f}")
            cells.append(ds + (" (worse)" if worse and key in accuracy_keys else ""))
        body.append([title] + cells)
    lines.append(_table(header, body, markdown))

    # per-file WER deltas (norm)
    if len(run_ids) >= 2:
        lines.append("")
        lines.append("per-file WER norm (%):")
        fh = ["file"] + list(run_ids) + ["Δ last vs first"]
        fb = []
        by_file = {r: {x["file_id"]: x for x in sub[r]} for r in run_ids}
        for fid in sorted({k[1] for k in common}):
            vals = []
            for r in run_ids:
                try:
                    vals.append(float(by_file[r][fid]["wer_norm"]))
                except (KeyError, ValueError):
                    vals.append(float("nan"))
            d = vals[-1] - vals[0]
            fb.append([fid] + [f"{100 * v:.2f}" for v in vals] + [f"{100 * d:+.2f}"])
        lines.append(_table(fh, fb, markdown))
    lines.append("")
    lines.append(
        "RESULT: " + ("REGRESSION - accuracy got worse than baseline" if regress else "OK - accuracy not worse than baseline")
        + f" (tolerance {100 * tol:.2f} pp on WER norm/punct/raw, cpWER, DER; first run is the baseline)"
    )
    return "\n".join(lines), regress


def md_report(rows: Sequence[dict[str, str]], run_ids: Sequence[str] | None = None, detail: bool = True) -> str:
    runs = group_runs(rows)
    ids = list(run_ids) if run_ids else sorted(runs, key=lambda r: runs[r][0].get("timestamp_utc", ""))
    out = ["# Результаты прогонов (stenoeval)", "", legend(), ""]
    head = ["run", "tier", "platform", "device", "dataset", "commit"] + [h for h, _, _ in SUMMARY_COLS]
    body = []
    for r in ids:
        g = runs[r]
        s = summarize(g)
        body.append(
            [r, g[0].get("tier", ""), g[0].get("platform", ""), g[0].get("device", ""), g[0].get("dataset", ""), g[0].get("commit", "")[:8]]
            + [f(s.get(k)) for _, k, f in SUMMARY_COLS]
        )
    out += ["## Сводка по прогонам", "", _table(head, body, True), ""]
    if detail:
        for r in ids:
            g = runs[r]
            out += [f"## {r}", "", f"tier={g[0].get('tier','')}, platform={g[0].get('platform','')}, "
                    f"device={g[0].get('device','')}, threads={g[0].get('num_threads','')}, "
                    f"dataset={g[0].get('dataset','')}, config_hash={g[0].get('config_hash','')}", ""]
            out += ["### По условиям", "", summary_table(g, markdown=True), ""]
            fh = ["file", "condition", "dur s", "spk ref/hyp", "WER raw", "WER norm", "cpWER", "DER", "JER", "RTF", "notes"]
            fb = []
            for x in g:
                free, _ = unpack_notes(x.get("notes", ""))
                def p(k: str) -> str:
                    v = x.get(k, "")
                    return "-" if v in ("", None) else f"{100 * float(v):.2f}"
                fb.append([
                    x["file_id"], x.get("condition", ""), fmt_num(float(x["duration_ms"]) / 1000 if x.get("duration_ms") else None, 1),
                    f"{x.get('num_speakers_ref','')}/{x.get('num_speakers_hyp','')}",
                    p("wer_raw"), p("wer_norm"), p("cpwer_norm"), p("der"), p("jer"),
                    x.get("rtf") or "-", free.replace("|", "/"),
                ])
            out += ["### По файлам", "", _table(fh, fb, True), ""]
    return "\n".join(out) + "\n"
