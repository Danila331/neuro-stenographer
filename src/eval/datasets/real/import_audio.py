#!/usr/bin/env python
"""Импорт реальных записей: python real/import_audio.py FILE... [--split dev|test] [--speakers N] [--name real]"""
import argparse
from pathlib import Path

import _boot  # noqa: F401
from stenodata.real import import_audio

ap = argparse.ArgumentParser(description=__doc__)
ap.add_argument("files", nargs="+", type=Path)
ap.add_argument("--name", default="real", help="имя датасета в .cache/data/ (по умолчанию real)")
ap.add_argument("--split", choices=["dev", "test"], help="по умолчанию — стабильный хэш (≈30%% test)")
ap.add_argument("--speakers", type=int, help="число говорящих, если известно")
ap.add_argument("--notes", default="", help="примечание (устройство, обстановка)")
a = ap.parse_args()
for i in import_audio(a.files, a.name, a.split, a.speakers, a.notes):
    print("imported", i)
