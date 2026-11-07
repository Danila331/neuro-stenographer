#!/usr/bin/env python3
"""Генератор assets/model_sets.json -- каталога наборов моделей для менеджера загрузки.

URL берутся из scripts/fetch_models.py (официальные источники: релизы sherpa-onnx на GitHub, Hugging Face csukuangfj/ggml-org);
список файлов набора -- из config/tiers/<tier>.json; размеры и sha256 -- из manifest.json, который пишет
scripts/stage_models.sh (если набор подготовлен), иначе из fetch_models.py.

    python3 src/android/app/tools/gen_model_sets.py [--staged-root DIR]... [--out FILE]

Размеры файлов берутся Range-запросом к официальному URL (нужна сеть), поэтому они всегда реальные.
Только стандартная библиотека Python.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
sys.path.insert(0, str(REPO / "scripts"))
import fetch_models as fm  # noqa: E402

GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
# В HF-репозитории csukuangfj/...parakeet_tdt_transducer_110m-en-36000-int8 нет файлов (только .gitattributes) --
# модель публикуется только архивом в релизах sherpa-onnx.
PARAKEET_ARCHIVE = {
    "id": "parakeet-110m",
    "url": f"{GH}/asr-models/sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8.tar.bz2",
    "format": "tar.bz2",
    "stripComponents": 1,
}
PARAKEET_DIR = "asr/parakeet-tdt-110m-en-int8"
PARAKEET_MEMBERS = ("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")

TITLES = {
    "lite": ("Lite", "Минимальная нагрузка: для слабых устройств."),
    "medium": ("Medium", "Основной набор: лучший баланс точности и скорости."),
    "hard": ("Hard", "Максимум точности для флагманов (от 8 ГБ ОЗУ)."),
}


def source_index() -> dict[str, "fm.F"]:
    idx: dict[str, fm.F] = {}
    for m in fm.MODELS:
        for f in m.files:
            path = f"llm/{f.dest}" if m.extra.get("shared_dir") else f"{m.dir}/{f.dest}"
            idx[path] = f
    for name in PARAKEET_MEMBERS:
        idx[f"{PARAKEET_DIR}/{name}"] = fm.F("archive:parakeet-110m", name)
    return idx


def config_paths(tier: str) -> list[str]:
    c = json.loads((REPO / "config/tiers" / f"{tier}.json").read_text(encoding="utf-8"))
    out: list[str] = [c["vad"]["model"]]
    dn = c.get("audio", {}).get("denoise", {})
    if dn.get("enabled") and dn.get("model"):
        out.append(dn["model"])
    out.append(c["asr"]["model_dir"])
    if (c["asr"].get("second_pass") or {}).get("model_dir"):
        out.append(c["asr"]["second_pass"]["model_dir"])
    if c.get("asr_en"):
        out.append(c["asr_en"]["model_dir"])
    if c.get("language", {}).get("lid_model"):
        out.append(c["language"]["lid_model"])
    out += [c["diarization"]["segmentation"], c["diarization"]["embedding"]]
    if c.get("punctuation", {}).get("model"):
        out.append(c["punctuation"]["model"])
    if c.get("llm", {}).get("model"):
        out.append(c["llm"]["model"])
    live = c.get("live") or {}
    if live.get("enabled") and live.get("model_dir"):  # потоковая модель живой расшифровки (B13); как в TierConfig.requiredModelPaths
        out.append(live["model_dir"])
    return list(dict.fromkeys(out))


def staged_manifest(tier: str, roots: list[Path]) -> dict[str, dict]:
    for r in roots:
        p = r / f"{tier}/assets/models/{tier}/manifest.json"
        if p.is_file():
            return {f["path"]: f for f in json.loads(p.read_text(encoding="utf-8"))["files"]}
    return {}


_sizes: dict[str, int] = {}


def remote_size(url: str) -> int:
    """Реальный размер файла по Range-запросу (после редиректов)."""
    if url not in _sizes:
        req = urllib.request.Request(url, headers={"Range": "bytes=0-0", "User-Agent": "neurosteno-gen/1.0"})
        with urllib.request.urlopen(req, timeout=60) as r:
            cr = r.headers.get("Content-Range", "")
            _sizes[url] = int(cr.rsplit("/", 1)[1]) if "/" in cr else int(r.headers.get("Content-Length") or 0)
    return _sizes[url]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--staged-root", action="append", default=[], help="каталог src/android/app/src с подготовленными assets")
    ap.add_argument("--out", default=str(HERE.parent / "src/main/assets/model_sets.json"))
    args = ap.parse_args()
    roots = [Path(p) for p in args.staged_root] + [HERE.parent / "src"]
    idx = source_index()
    sets, bad = [], 0
    archives: dict[str, dict] = {}
    for tier in ("lite", "medium", "hard"):
        staged = staged_manifest(tier, roots)
        files = []
        for base in config_paths(tier):
            keys = sorted(k for k in idx if k == base or k.startswith(base + "/"))
            if not keys:
                print(f"[{tier}] нет источника для {base}", file=sys.stderr)
                bad += 1
                continue
            for path in keys:
                f = idx[path]
                st = staged.get(path, {})
                entry: dict = {"path": path}
                if f.url.startswith("archive:"):
                    if st.get("size") is None:
                        print(f"[{tier}] {path}: нет размера (подготовьте набор stage_models.sh)", file=sys.stderr)
                        bad += 1
                        continue
                    entry["archive"] = f.url.split(":", 1)[1]
                    entry["member"] = f.dest
                    entry["size"] = int(st["size"])
                    if st.get("sha256"):
                        entry["sha256"] = st["sha256"]
                    a = dict(PARAKEET_ARCHIVE)
                    a["size"] = remote_size(a["url"])
                    archives[a["id"]] = a
                else:
                    size = remote_size(f.url)  # реальный размер важнее оценок в fetch_models.py и локальных подмен при staging
                    sha = f.sha256 or (st.get("sha256") if st.get("size") == size else None)
                    entry.update(url=f.url, size=size)
                    if sha:
                        entry["sha256"] = sha
                    if f.size and abs(f.size - size) > max(1, f.size // 100):
                        print(f"[{tier}] {path}: fetch_models.py size={f.size}, реально {size}", file=sys.stderr)
                files.append(entry)
        title, desc = TITLES[tier]
        sets.append({"id": tier, "tier": tier, "title": title, "description": desc,
                     "recommended": tier == "medium", "version": "1", "files": files})
    catalog = {"version": 1,
               "_note": "Сгенерировано src/android/app/tools/gen_model_sets.py; URL -- официальные источники (scripts/fetch_models.py)",
               "archives": list(archives.values()), "sets": sets}
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for s in sets:
        total = sum(f["size"] for f in s["files"])
        print(f"{s['id']}: {len(s['files'])} файлов, {total / 1e6:.0f} МБ")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
