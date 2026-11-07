"""Pretty tables from results/retr_*.json:  python -m bench_rag.report_retrieval results/retr_scoped.json results/retr_qwen3.json --chunk turn --corpus asr"""
import argparse
import json


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--chunk", default="turn")
    ap.add_argument("--corpus", default="asr")
    a = ap.parse_args()
    rows = [r for f in a.files for r in json.load(open(f)) if r["chunk"] == a.chunk and r["corpus"] == a.corpus]
    print(f"| model | mode | hit@1 | hit@5 | recall@5 | MRR | speaker q | name q | topic q | history q |  (corpus={a.corpus}, chunk={a.chunk}, n={rows[0]['n'] if rows else 0})")
    for r in sorted(rows, key=lambda r: (r["model"], r.get("mode", ""))):
        g = lambda k: r.get(k, {}).get("hit5", "-")
        print(f"| {r['model']} | {r.get('mode', 'bm25')} | {r['all']['hit1']} | {r['all']['hit5']} | {r['all']['recall5']} | {r['all']['mrr']} | {g('speaker')} | {g('name')} | {g('topic')} | {g('history')} |")


if __name__ == "__main__":
    main()
