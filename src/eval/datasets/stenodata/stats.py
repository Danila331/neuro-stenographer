"""Статистика по датасетам -> markdown."""
from __future__ import annotations

import csv
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

from .common import OUR_DATASETS, data_root


def dataset_stats(root: Path) -> dict:
    with open(root / "manifest.csv", encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    tot_ms = sum(int(r["duration_ms"]) for r in rows)
    by_cond = defaultdict(lambda: [0, 0])
    by_src = defaultdict(lambda: [0, 0])
    by_split = defaultdict(lambda: [0, 0])
    by_scen = defaultdict(lambda: [0, 0])
    spk = Counter()
    ov_ms = 0.0
    n_utts, speech_ms = 0, 0
    words = 0
    for r in rows:
        d = int(r["duration_ms"])
        for tbl, k in ((by_cond, r["condition"]), (by_src, r["source"]), (by_split, r["split"])):
            tbl[k][0] += 1
            tbl[k][1] += d
        if r.get("scenario"):
            by_scen[r["scenario"]][0] += 1
            by_scen[r["scenario"]][1] += d
        spk[r["num_speakers"]] += 1
        if r.get("overlap_frac"):
            ov_ms += float(r["overlap_frac"]) * d
        obj = json.loads((root / r["ref"]).read_text(encoding="utf-8"))
        for u in obj["transcript"]:
            n_utts += 1
            speech_ms += u["end_ms"] - u["start_ms"]
            words += len(u["text"].split())
    return dict(name=root.name, n=len(rows), hours=tot_ms / 3.6e6, by_cond=dict(by_cond), by_src=dict(by_src),
                by_split=dict(by_split), by_scen=dict(by_scen), spk=dict(sorted(spk.items())), overlap_ratio=(ov_ms / tot_ms if tot_ms else 0.0),
                n_utts=n_utts, speech_hours=speech_ms / 3.6e6, words=words)


def fmt_table(title: str, d: dict) -> str:
    out = [f"| {title} | файлов | часов |", "|---|---|---|"]
    for k, (n, ms) in sorted(d.items()):
        out.append(f"| {k} | {n} | {ms/3.6e6:.2f} |")
    return "\n".join(out)


def to_markdown(s: dict) -> str:
    md = [f"### {s['name']}", "",
          f"- файлов: **{s['n']}**, суммарно **{s['hours']:.2f} ч**; реплик: {s['n_utts']}, слов: {s['words']}, "
          f"речи по ref: {s['speech_hours']:.2f} ч",
          f"- число говорящих (файлов): {s['spk']}",
          f"- доля времени с перекрытием речи: {100*s['overlap_ratio']:.1f} %",
          "", fmt_table("condition", s["by_cond"]), "", fmt_table("source", s["by_src"]), "",
          fmt_table("split", s["by_split"]), ""]
    if s.get("by_scen"):
        md += [fmt_table("scenario (call/meeting)", s["by_scen"]), ""]
    return "\n".join(md)


def update_readme(parts: list[str]):
    readme = Path(__file__).resolve().parent.parent / "README.md"
    txt = readme.read_text(encoding="utf-8")
    b, e = "<!-- STATS:BEGIN -->", "<!-- STATS:END -->"
    if b in txt and e in txt:
        txt = txt[: txt.index(b) + len(b)] + "\n" + "\n".join(parts) + "\n" + txt[txt.index(e):]
        readme.write_text(txt, encoding="utf-8")


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    upd = "--update-readme" in argv
    argv = [a for a in argv if a != "--update-readme"]
    names = argv or [n for n in OUR_DATASETS if (data_root() / n / "manifest.csv").exists()]
    parts = []
    for n in names:
        root = data_root() / n
        md = to_markdown(dataset_stats(root))
        (root / "STATS.md").write_text(md, encoding="utf-8")
        parts.append(md)
    print("\n".join(parts))
    if upd:
        update_readme(parts)


if __name__ == "__main__":
    main()
