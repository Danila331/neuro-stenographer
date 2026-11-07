"""Table from results/answer_*.json:  python -m bench_rag.report_answer results/answer_*.json"""
import json
import sys

import numpy as np


def main(files):
    print("| file | n | answer has key fact | + cites the gold fragment (strict) | citations valid | negatives refused | positives wrongly refused | s/question (PC) | prompt/gen tok |")
    for f in files:
        d = json.load(open(f))
        rows = d["rows"]
        pos = [r for r in rows if not r["neg"]]
        neg = [r for r in rows if r["neg"]]
        s = d["summary"]
        strict = np.mean([r["correct"] and r["cited_gold"] and not r["refused"] for r in pos])
        print(f"| {f.split('/')[-1][7:-5]} | {len(pos)}+{len(neg)} | {np.mean([r['correct'] for r in pos]):.2f} | {strict:.2f} | {s['cite_valid']} | {s['neg_refused']} | {np.mean([r['refused'] for r in pos]):.2f} | {s['s_per_q_pc']} | {s['prompt_tok']}/{s['gen_tok']} |")


if __name__ == "__main__":
    main(sys.argv[1:])
