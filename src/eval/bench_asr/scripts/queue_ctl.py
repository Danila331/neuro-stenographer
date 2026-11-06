"""Управление очередью: python scripts/queue_ctl.py reset|add 'команда'|status"""
import sys, os
from pathlib import Path
J = Path(__file__).resolve().parents[1] / "jobs"; J.mkdir(exist_ok=True)
cmd = sys.argv[1]
if cmd == "reset":
    (J / "jobs.txt").write_text(""); (J / "idx").write_text("0")
elif cmd == "add":
    with open(J / "jobs.txt", "a") as f:
        f.write(" ".join(sys.argv[2:]).replace("\n", " ") + "\n")
elif cmd == "status":
    idx = int((J / "idx").read_text()); L = (J / "jobs.txt").read_text().splitlines()
    print(f"done {idx}/{len(L)}; current: {L[idx][:140] if idx < len(L) else '-'}")
    print("\n".join((J / "queue.log").read_text().splitlines()[-3:]) if (J / "queue.log").exists() else "")
