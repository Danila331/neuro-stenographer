import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

reports = []
for arg in sys.argv[1:]:
    root = Path(arg)
    files = sorted(root.glob("TEST-*.xml")) if root.is_dir() else [root]
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for path in files:
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(float(suite.attrib.get(key, 0)))
    totals["path"] = str(root)
    totals["xml_files"] = len(files)
    reports.append(totals)
print(json.dumps(reports, ensure_ascii=False, indent=2))
if any(r["failures"] or r["errors"] or not r["xml_files"] for r in reports):
    raise SystemExit(1)
