from pathlib import Path

from bench_llm.android_flow import combine, content_budget, lines_of, qa_material, read
from bench_llm.data import load_dialogs

ROOT = Path(__file__).resolve().parents[1]
question = "Сколько будут платить дворникам после повышения?"
system = read(ROOT / "src/eval/bench_llm/prompts/android", "qa_system.txt")
budget = content_budget(4096, 400, len(system) + len(question))
lines = lines_of(combine(load_dialogs())[0])
print(f"budget={budget}")
for ratio in (0.25, 0.35, 0.5, 1.0):
    parts = qa_material(lines, question, budget, ratio)
    print(f"\n=== ratio={ratio} chars={sum(map(len, parts))} ===")
    print("\n...\n".join(parts))
