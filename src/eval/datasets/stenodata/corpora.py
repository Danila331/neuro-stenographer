"""Загрузчики исходных корпусов из `.cache/data/.raw/<name>/` -> единый список Clip."""
from __future__ import annotations

import csv
import json
import re
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from .common import raw_root, read_audio_16k

LICENSES = {
    "ruls": "Public Domain (LibriVox), Russian LibriSpeech OpenSLR SLR96; зеркало HF istupakov/russian_librispeech (CC-BY-4.0 карточка)",
    "voxforge": "GPL-3.0 (VoxForge, http://www.voxforge.org/ru/downloads)",
    "fleurs": "CC-BY-4.0 (google/fleurs)",
    "librispeech": "CC-BY-4.0 (LibriSpeech, OpenSLR SLR12)",
    "fleurs_en": "CC-BY-4.0 (google/fleurs)",
    "golos_crowd": "SberDevices Golos license (https://github.com/sberdevices/golos/blob/master/license/en_us.pdf), OpenSLR SLR114",
    "golos_farfield": "SberDevices Golos license (https://github.com/sberdevices/golos/blob/master/license/en_us.pdf), OpenSLR SLR114",
    "musan": "CC-BY-4.0 / ранее PD-подмножества (OpenSLR SLR17, music/fma)",
    "esc50": "CC BY-NC 3.0 (ESC-50, Piczak), клипы CC BY/CC0 с freesound",
    "piper": "голоса rhasspy/piper-voices ru_RU: denis/dmitri/ruslan/irina — см. MODEL_CARD каждого голоса (CC0/Public-domain-подобные)",
}


@dataclass
class Clip:
    id: str
    path: Path
    text: str
    speaker: str | None
    source: str
    ref_style: str               # punct_case | norm (нижний регистр без пунктуации)
    gender: str | None = None
    extra: dict = field(default_factory=dict)

    def audio(self) -> np.ndarray:
        return read_audio_16k(self.path)


def ref_style_of(text: str) -> str:
    """punct_case: пунктуация и/или смешанный регистр; upper_nopunct: ВЕРХНИЙ РЕГИСТР без знаков (LibriSpeech);
    lower_nopunct: нижний регистр без знаков (Golos)."""
    letters = [c for c in text if c.isalpha()]
    if re.search(r"[.,!?;:—–\-«»\"()…]", text):
        return "punct_case"
    if letters and all(c.isupper() for c in letters):
        return "upper_nopunct"
    return "punct_case" if any(c.isupper() for c in letters) else "lower_nopunct"


def _parse_vf_readme(p: Path) -> dict:
    d = {}
    if p.exists():
        for line in p.read_text(encoding="utf-8", errors="replace").splitlines():
            if ":" in line:
                k, v = line.split(":", 1)
                d[k.strip()] = v.strip()
    return d


def load_voxforge(root: Path | None = None) -> list[Clip]:
    root = root or (raw_root() / "voxforge")
    clips = []
    for sess in sorted(p for p in root.iterdir() if p.is_dir()):
        wavs = sess / "wav"
        prompts = sess / "etc" / "prompts-original"
        if not wavs.is_dir() or not prompts.exists():
            continue
        user = re.sub(r"-\d{8}.*", "", sess.name)
        speaker = f"vf_{sess.name}" if user == "anonymous" else f"vf_{user}"
        meta = _parse_vf_readme(sess / "etc" / "README")
        gender = {"Мужчина": "M", "Женщина": "F", "Male": "M", "Female": "F"}.get(meta.get("Gender", ""))
        for line in prompts.read_text(encoding="utf-8", errors="replace").splitlines():
            parts = line.strip().split(None, 1)
            if len(parts) != 2:
                continue
            uid, text = parts
            wav = wavs / f"{uid}.wav"
            if not wav.exists():
                continue
            text = text.strip()
            if not text:
                continue
            clips.append(Clip(id=f"vf_{sess.name}_{uid}", path=wav, text=text, speaker=speaker, source="voxforge",
                              ref_style=ref_style_of(text), gender=gender,
                              extra={"mic": meta.get("Microphone type", ""), "session": sess.name}))
    return clips


def load_fleurs(root: Path | None = None, prefix: str = "fleurs", source: str = "fleurs") -> list[Clip]:
    root = root or (raw_root() / "fleurs")
    clips = []
    for sp in ("dev", "test"):
        tsv = root / f"{sp}.tsv"
        if not tsv.exists():
            continue
        with open(tsv, encoding="utf-8") as f:
            for row in csv.reader(f, delimiter="\t"):
                if len(row) < 7:
                    continue
                uid, fn, raw, norm = row[0], row[1], row[2], row[3]
                wav = root / "audio" / sp / fn
                if not wav.exists():
                    continue
                clips.append(Clip(id=f"{prefix}_{sp}_{uid}", path=wav, text=raw.strip(), speaker=None, source=source,
                                  ref_style="punct_case", gender={"MALE": "M", "FEMALE": "F"}.get(row[6].strip().upper()),
                                  extra={"norm_text": norm.strip()}))
    return clips


def load_golos(root: Path | None = None) -> list[Clip]:
    """Golos test (crowd + farfield). Манифесты jsonl лежат рядом с аудио."""
    root = root or (raw_root() / "golos")
    clips = []
    for dom in ("crowd", "farfield"):
        base = root / "test_opus" / dom
        man = next(iter(sorted(base.glob("*.jsonl"))), None)
        if man is None:
            continue
        with open(man, encoding="utf-8") as f:
            for line in f:
                if not line.strip():
                    continue
                d = json.loads(line)
                rel = d.get("audio_filepath") or d.get("filepath") or d.get("path")
                p = base / rel if rel and not str(rel).startswith("test_opus") else root / rel
                if not p.exists():
                    p2 = base / "files" / Path(rel).name
                    if p2.exists():
                        p = p2
                    else:
                        continue
                text = (d.get("text") or "").strip()
                if not text:
                    continue
                clips.append(Clip(id=f"golos_{dom}_{p.stem}", path=p, text=text, speaker=None, source=f"golos_{dom}",
                                  ref_style=ref_style_of(text),
                                  extra={"duration": d.get("duration"), "domain": dom}))
    return clips


def load_ruls(root: Path | None = None) -> list[Clip]:
    """RuLS test: диктор = READER_ID из пути audio/<reader>/<book>/..."""
    root = root or (raw_root() / "ruls")
    man = root / "manifest.jsonl"
    if not man.exists():
        return []
    genders = {}
    cr = root / "CHAPTERS_READERS.TXT"
    if cr.exists():
        for line in cr.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.startswith(";") or "|" not in line:
                continue
            parts = [x.strip() for x in line.split("|")]
            if len(parts) >= 6:
                genders[parts[3]] = parts[5]
    clips = []
    with open(man, encoding="utf-8") as f:
        for line in f:
            d = json.loads(line)
            p = root / d["audio_filepath"]
            if not p.exists():
                continue
            parts = Path(d["audio_filepath"]).parts  # test/audio/<spk>/<book>/<file>
            spk = parts[2]
            text = (d.get("text_raw") or d.get("text") or "").strip()
            if not text:
                continue
            clips.append(Clip(id=f"ruls_{spk}_{p.stem}", path=p, text=text, speaker=f"ruls_{spk}", source="ruls",
                              ref_style=ref_style_of(text), gender=genders.get(spk),
                              extra={"duration": d.get("duration")}))
    return clips


def load_librispeech(root: Path | None = None) -> list[Clip]:
    """LibriSpeech test-clean + test-other: LibriSpeech/<subset>/<spk>/<chapter>/<spk>-<chapter>-<utt>.flac."""
    root = root or (raw_root() / "librispeech")
    clips = []
    for tr in sorted(root.rglob("*.trans.txt")):
        subset = tr.parent.parent.parent.name  # test-clean / test-other
        spk = tr.parent.parent.name
        for line in tr.read_text(encoding="utf-8").splitlines():
            parts = line.strip().split(" ", 1)
            if len(parts) != 2:
                continue
            uid, text = parts
            p = tr.parent / f"{uid}.flac"
            if p.exists():
                clips.append(Clip(id=f"ls_{subset}_{uid}", path=p, text=text.strip(), speaker=f"ls_{spk}", source=f"librispeech_{subset}",
                                  ref_style=ref_style_of(text), extra={"subset": subset}))
    return clips


def load_fleurs_en(root: Path | None = None) -> list[Clip]:
    return load_fleurs(root or (raw_root() / "fleurs_en"), prefix="fleurs_en", source="fleurs_en")
