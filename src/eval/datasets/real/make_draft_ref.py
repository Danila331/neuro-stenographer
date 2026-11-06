#!/usr/bin/env python
"""Черновик эталона из JSON-гипотезы пайплайна (формат выхода): python real/make_draft_ref.py HYP.json ID

Создаёт <real>/annotation/<ID>.txt (для ручной правки) и <real>/ref/<ID>.draft.json.
После правки: python real/compile_ref.py ID
"""
import argparse
from pathlib import Path

import _boot  # noqa: F401
from stenodata.real import hypothesis_to_draft

ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("hypothesis", type=Path)
ap.add_argument("id", help="id записи из manifest.csv датасета real")
ap.add_argument("--name", default="real")
a = ap.parse_args()
print(hypothesis_to_draft(a.hypothesis, a.id, a.name))
