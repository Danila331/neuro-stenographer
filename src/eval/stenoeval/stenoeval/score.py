"""`stenoeval score`: evaluate a run directory against a dataset and produce results.csv rows."""

from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from . import dataset as ds
from .evaluate import PairMetrics, evaluate_pair
from .results import pack_notes
from .timing import hyp_words_from_full
from .transcript import StrictJSONError, Utt, load_transcript, read_text_strict, sort_utts, strict_loads
from .validate import validate_file


@dataclass
class ScoreOptions:
    dataset_root: Path
    run_dir: Path
    run_id: str | None = None
    tier: str | None = None
    platform: str = "desktop"
    device: str | None = None
    num_threads: str | None = None
    notes: str = ""
    split: str | None = None            # dev | test | None = all
    ids: list[str] | None = None
    only_present: bool = False          # skip files without hypothesis (default: count them as empty)
    dataset_name: str | None = None
    commit: str | None = None


@dataclass
class FileScore:
    item: ds.Item
    metrics: PairMetrics
    row: dict[str, object]
    flags: list[str] = field(default_factory=list)
    ts_source: str = "utt"


def git_commit(cwd: Path | None = None) -> str:
    try:
        out = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=cwd, capture_output=True, text=True, timeout=10, check=True
        )
        return out.stdout.strip()
    except Exception:
        return "unknown"


def _r(v: float | None, nd: int) -> object:
    return "" if v is None else round(v, nd)


def run_score(opt: ScoreOptions) -> tuple[list[FileScore], list[str]]:
    """Returns (scores, warnings)."""
    warnings: list[str] = []
    items = ds.read_manifest(opt.dataset_root)
    if opt.split and opt.split != "all":
        items = [i for i in items if i.split == opt.split]
    if opt.ids:
        want = set(opt.ids)
        unknown = want - {i.id for i in items}
        if unknown:
            warnings.append(f"ids not in manifest (after filters): {sorted(unknown)}")
        items = [i for i in items if i.id in want]
    if not items:
        raise SystemExit("no files to score (empty manifest or filters matched nothing)")

    run_id = opt.run_id or opt.run_dir.resolve().name
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    commit = opt.commit or git_commit(Path.cwd())
    dataset_name = opt.dataset_name or opt.dataset_root.resolve().name
    out: list[FileScore] = []
    missing: list[str] = []

    for it in items:
        flags: list[str] = []
        hyp_path = opt.run_dir / f"{it.id}.json"
        if not it.ref_path.is_file():
            raise SystemExit(f"reference not found for {it.id}: {it.ref_path}")
        ref = sort_utts(load_transcript(it.ref_path))
        duration = it.duration_ms
        if duration is None and it.audio_path is not None:
            duration = ds.wav_duration_ms(it.audio_path)
        if duration is None:
            duration = max((u.end_ms for u in ref), default=None)
            warnings.append(f"{it.id}: no duration_ms in manifest/audio; using last reference end")

        hyp: list[Utt] = []
        if not hyp_path.is_file():
            missing.append(it.id)
            if opt.only_present:
                continue
            flags.append("missing_hyp")
        else:
            vr = validate_file(hyp_path, duration)
            if not vr.ok:
                flags.append("invalid_hyp")
                warnings.append(f"{it.id}: hypothesis is not valid per spec ({len(vr.errors)} errors; first: {vr.errors[0]})")
            try:
                hyp = sort_utts(load_transcript(hyp_path))
            except (StrictJSONError, ValueError, OSError) as e:
                flags.append("unparseable_hyp")
                warnings.append(f"{it.id}: cannot parse hypothesis, scored as empty ({e})")

        hyp_words = None
        full_path = opt.run_dir / f"{it.id}.full.json"
        if full_path.is_file():
            try:
                hyp_words = hyp_words_from_full(strict_loads(read_text_strict(full_path)))
            except (StrictJSONError, ValueError, OSError):
                hyp_words = None
            if hyp_words is None:
                warnings.append(f"{it.id}: {full_path.name} unusable, timestamps use proportional word split")
        ts_source = "words" if hyp_words is not None else "utt"
        pm = evaluate_pair(ref, hyp, duration, hyp_words)
        meta = ds.read_meta(opt.run_dir, it.id)
        proc = meta.get("processing_ms")
        proc = float(proc) if isinstance(proc, (int, float)) and not isinstance(proc, bool) else None
        rtf = proc / duration if proc is not None and duration else None
        t = pm.timing
        assert t is not None
        w = pm.wer
        counters: dict[str, object] = {
            "refw": w["norm"].ref_words, "werr": w["norm"].errors,
            "refw_raw": w["raw"].ref_words, "werr_raw": w["raw"].errors,
            "refw_num": w["norm_num"].ref_words, "werr_num": w["norm_num"].errors,
            "refw_hy": w["norm_hy"].ref_words, "werr_hy": w["norm_hy"].errors,
            "cperr": pm.cpwer["norm"].counts.errors,
            "dtot": round(pm.der["c0"].total, 3), "derr": round(pm.der["c0"].errors, 3),
            "dtot_c": round(pm.der["nist"].total, 3), "derr_c": round(pm.der["nist"].errors, 3),
            "tsn": t.n_matched, "nutt": t.n_ref_utts,
            "ts_acc300": None if t.start_acc300 is None else round(t.start_acc300, 4),
            "ts": ts_source,
            "flag": ",".join(flags) if flags else None,
        }
        n_ref_spk = it.num_speakers if it.num_speakers is not None else pm.spk_ref
        if it.num_speakers is not None and it.num_speakers != pm.spk_ref:
            warnings.append(f"{it.id}: manifest num_speakers={it.num_speakers} but reference file has {pm.spk_ref} speakers")
        row: dict[str, object] = {
            "run_id": run_id,
            "timestamp_utc": now,
            "commit": commit,
            "tier": opt.tier or meta.get("tier") or "",
            "config_hash": meta.get("config_hash") or "",
            "platform": opt.platform,
            "device": opt.device or meta.get("device") or "",
            "num_threads": opt.num_threads or meta.get("num_threads") or "",
            "dataset": dataset_name,
            "file_id": it.id,
            "condition": it.condition,
            "duration_ms": duration if duration is not None else "",
            "num_speakers_ref": n_ref_spk,
            "num_speakers_hyp": pm.spk_hyp,
            "wer_raw": _r(w["raw"].wer, 5),
            "wer_norm": _r(w["norm"].wer, 5),
            "cpwer_norm": _r(pm.cpwer["norm"].wer, 5),
            "der": _r(pm.der["c0"].der, 5),
            "der_collar": _r(pm.der["nist"].der, 5),
            "jer": _r(pm.jer, 5),
            "ts_start_mae_ms": _r(t.start_mae_ms, 1),
            "ts_end_mae_ms": _r(t.end_mae_ms, 1),
            "processing_ms": _r(proc, 1),
            "rtf": _r(rtf, 4),
            "notes": pack_notes(opt.notes, counters),
        }
        out.append(FileScore(it, pm, row, flags, ts_source))

    if missing:
        what = "skipped (--only-present)" if opt.only_present else "scored as EMPTY hypothesis (WER 100%)"
        warnings.append(f"{len(missing)}/{len(items)} files have no hypothesis in {opt.run_dir}: {what}: {missing[:8]}")
    return out, warnings


def details_json(scores: list[FileScore]) -> list[dict]:
    """Full per-file detail (all normalisation modes / collars) for --json-out."""
    res = []
    for s in scores:
        pm = s.metrics
        t = pm.timing
        res.append(
            {
                "file_id": s.item.id,
                "condition": s.item.condition,
                "duration_ms": pm.duration_ms,
                "flags": s.flags,
                "wer": {m: {"ref_words": c.ref_words, "sub": c.sub, "del": c.dele, "ins": c.ins, "wer": c.wer} for m, c in pm.wer.items()},
                "cpwer": {m: {"errors": c.counts.errors, "ref_words": c.counts.ref_words, "wer": c.wer, "mapping": c.mapping} for m, c in pm.cpwer.items()},
                "der": {
                    k: {"total_s": d.total, "missed_s": d.missed, "false_alarm_s": d.false_alarm, "confusion_s": d.confusion, "der": d.der}
                    for k, d in pm.der.items()
                },
                "jer": pm.jer,
                "jer_nist": pm.jer_nist,
                "speakers": {"ref": pm.spk_ref, "hyp": pm.spk_hyp, "correct": pm.spk_correct},
                "timestamps": None
                if t is None
                else {
                    "ref_utterances": t.n_ref_utts, "matched": t.n_matched,
                    "start_mae_ms": t.start_mae_ms, "end_mae_ms": t.end_mae_ms,
                    "start_median_ms": t.start_median_ms, "end_median_ms": t.end_median_ms,
                    "start_acc300": t.start_acc300, "end_acc300": t.end_acc300,
                },
                "ts_source": s.ts_source,
                "row": s.row,
            }
        )
    return res


def dump_json(path: Path, scores: list[FileScore]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(details_json(scores), ensure_ascii=False, indent=2), encoding="utf-8")
