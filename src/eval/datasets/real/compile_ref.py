#!/usr/bin/env python
"""Собрать ref/<ID>.json из annotation/<ID>.txt после ручной правки: python real/compile_ref.py ID [ID...]"""
import argparse

import _boot  # noqa: F401
from stenodata.real import compile_ref

ap = argparse.ArgumentParser(description=__doc__)
ap.add_argument("ids", nargs="+")
ap.add_argument("--name", default="real")
a = ap.parse_args()
for i in a.ids:
    print(compile_ref(i, a.name))
