from pathlib import Path

from bench_llm.android_flow import chunk, combine, content_budget, lines_of, read
from bench_llm.data import load_dialogs

ROOT = Path(__file__).resolve().parents[1]
dialogs = load_dialogs()
sets = {d.id: combine([d])[0] for d in dialogs}
sets["long"] = combine(dialogs)[0]
system = read(ROOT / "src/eval/bench_llm/prompts/android", "protocol_system.txt")
current = content_budget(4096, 450, len(system))
print(f"system_chars={len(system)} current_budget={current}")
for name, items in sets.items():
    lines = lines_of(items)
    print(f"\n{name}: lines={len(lines)} chars={sum(len(x) + 1 for x in lines)}")
    for cap in (current, 5000, 4500, 4000, 3500, 3000, 2500):
        parts = chunk(lines, min(current, cap))
        print(f"  cap={cap}: n={len(parts)} sizes={[len(p) for p in parts]}")
