import json
import math
import re
from pathlib import Path

from bench_llm.android_flow import chunk, combine, content_budget, lines_of, read
from bench_llm.data import load_dialogs
from bench_llm.text import norm

ROOT = Path(__file__).resolve().parents[1]
STOP = {"сколько", "какой", "какая", "какие", "как", "что", "когда", "где", "почему", "зачем", "быть", "будут", "будет", "есть", "после", "перед", "для", "это", "все", "всего", "может", "надо", "нужно"}


def stems(text, size, stop):
    return {w[:size] for w in re.findall(r"[\w]+", text.lower().replace("ё", "е")) if len(w) >= 4 and (not stop or w not in STOP)}


def select(chunks, question, budget, size, stop):
    keys = stems(question, size, stop)
    doc = [stems(c, size, stop) for c in chunks]
    df = {k: sum(k in cs for cs in doc) for k in keys}
    def idf(k):
        f = df[k]
        return math.log((len(chunks) - f + 0.5) / (f + 0.5) + 1.0)
    scored = sorted(((i, sum(idf(k) for k in keys if k in doc[i])) for i in range(len(chunks))), key=lambda x: (-x[1], x[0]))
    chosen, used = [], 0
    for i, _ in scored:
        if used + len(chunks[i]) > budget:
            continue
        chosen.append(i)
        used += len(chunks[i])
    return [chunks[i] for i in sorted(chosen)]


gold = json.loads((ROOT / "src/eval/bench_llm/data/protocol_gold.json").read_text(encoding="utf-8"))
questions = [q for group in gold.values() for q in group["qa"]]
system = read(ROOT / "src/eval/bench_llm/prompts/android", "qa_system.txt")
lines = lines_of(combine(load_dialogs())[0])
for size, stop in ((5, False), (5, True), (4, False), (4, True)):
    hits, chars = 0, []
    misses = []
    for q in questions:
        budget = int(content_budget(4096, 400, len(system) + len(q["q"])) * 0.25)
        chunks = chunk(lines, budget)
        material = " ".join(select(chunks, q["q"], budget, size, stop))
        chars.append(len(material))
        answer_keys = [k for k in q["any"] if len(norm(k)) >= 4 and not norm(k).isdigit()]
        ok = any(norm(k) in norm(material) for k in answer_keys)
        hits += ok
        if not ok:
            misses.append(q["q"])
    print(f"stem={size} stop={stop}: retrieval={hits}/{len(questions)} avg_chars={sum(chars)/len(chars):.0f} misses={misses}")
