"""Генерирует JSON-кейсы паритета Kotlin <-> bench_rag для `ru.neurosteno.core.summary` (summary_cases.json) ПРЯМО из функций эталона:
стеммер/термы, долины (boundaries) и план фрагментов, GBNF, разбор+проверка ссылок (validate), склейка тем (merge), JSON для UI (to_json),
extractive-выжимка. Запуск (venv бенчмарка):

  src/eval/bench_rag/.venv/bin/python src/android/scripts/gen_summary_cases.py   # -> core/src/test/resources/summary_cases.json
"""
import json
import random
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(REPO / "src/eval/bench_rag"))

from bench_rag import extractive as X  # noqa: E402
from bench_rag import summary as S  # noqa: E402
from bench_rag.data import ASR_DIR, Record, Utt  # noqa: E402
from bench_rag.segment import boundaries, lexical_vectors  # noqa: E402
from bench_rag.sumdata import all_docs  # noqa: E402
from bench_rag.text import norm_term, stem, terms, tokens  # noqa: E402

OUT = HERE.parent / "core/src/test/resources/summary_cases.json"
rng = random.Random(20261107)


def utts_json(rec):
    return [[u.speaker, u.start_ms, u.end_ms, u.text] for u in rec.utts]


def rec_of(rows):
    return Record("x", [Utt(i, r[0], r[1], r[2], r[3]) for i, r in enumerate(rows)])


def topics_json(rec, topics):
    return S.to_json(rec, topics)


def load_pipeline(name):
    tr = json.loads((ASR_DIR / f"{name}.json").read_text(encoding="utf-8"))["transcript"]
    return Record(name, [Utt(i, u["speaker_id"], u["start_ms"], u["end_ms"], u["text"]) for i, u in enumerate(tr)])


def main():
    docs = {d.id: d.rec for d in all_docs("asr")}
    real = {n: load_pipeline(n) for n in ["cats_c01_v0_meeting_clean", "cats_c09_v0_meeting_overlap", "synth_0000_meeting_overlap"] if (ASR_DIR / f"{n}.json").exists()}
    recs = {**{k: docs[k] for k in ["M1", "M3", "M7", "d1_budget", "d3_holiday"]}, **real}
    cases = {}

    # ---- 1. стеммер и термы
    words = set()
    for r in recs.values():
        for u in r.utts:
            words.update(tokens(u.text))
    words.update(["сметаны", "сметану", "ёлка", "мышей", "говорящий", "обсуждали", "ремонтировать", "тихона", "а", "11", "ъ", "сь", "вшись", "ость"])
    cases["stem"] = [[w, stem(w), norm_term(w, "snow5"), norm_term(w, "trunc5")] for w in sorted(words)]
    texts = ["Что говорил говорящий 2 про бюджет на рыбу?", "Ёлки-палки, 12 котов и три кошки!", "а б в 5 x y", "Решили: ловить мышей на складе до пятницы."]
    texts += [u.text for u in docs["M2"].utts[:12]]
    cases["terms"] = [[t, terms(t), terms(t, drop_query_words=True), terms(t, mode="snow5")] for t in texts]

    # ---- 2. долины
    seg = []
    for i in range(12):
        n = rng.randint(8, 70)
        d = rng.choice([2, 5, 16])
        centers = [np.array([rng.uniform(-1, 1) for _ in range(d)]) for _ in range(4)]
        rows, cur = [], 0
        vecs = []
        for j in range(n):
            if rng.random() < 0.12:
                cur = rng.randrange(4)
            v = centers[cur] + np.array([rng.gauss(0, 0.35) for _ in range(d)])
            vecs.append([round(float(x), 6) for x in v])
        chars = [rng.randint(20, 220) for _ in range(n)]
        k, alpha, mu, mc, mx = rng.choice([(3, 0.0, 3, 300, 1500), (2, 0.5, 3, 300, 2400), (3, 0.0, 2, 120, 700), (4, 1.0, 3, 300, 1500), (3, 0.0, 3, 300, 400)])
        exp = boundaries(np.array(vecs), chars, k=k, alpha=alpha, min_utts=mu, max_chars=mx, min_chars=mc)
        seg.append({"vecs": vecs, "chars": chars, "k": k, "alpha": alpha, "min_utts": mu, "min_chars": mc, "max_chars": mx, "expected": [int(x) for x in exp]})
    cases["segment"] = seg

    # ---- 3. план (размер / лексика), грамматика, лексические векторы
    plans = []
    for name, rec in recs.items():
        lex = lexical_vectors([u.text for u in rec.utts])
        for k in (3, 4):
            plans.append({"doc": name, "k": k, "alpha": 0.0, "target": 1500, "kind": "lexical", "expected": [list(f) for f in S.plan(rec, lex, 1500, k=k, alpha=0.0)]})
        plans.append({"doc": name, "target": 1500, "kind": "size", "expected": [list(f) for f in S.plan(rec, None, 1500)]})
        plans.append({"doc": name, "target": 700, "kind": "size", "expected": [list(f) for f in S.plan(rec, None, 700)]})
    cases["plan"] = plans
    cases["docs"] = {name: utts_json(rec) for name, rec in recs.items()}

    gr = []
    for name, rec in recs.items():
        for lo, hi in S.plan(rec, None, 900)[:4]:
            gr.append({"doc": name, "lo": lo, "hi": hi, "expected": S.grammar(rec, lo, hi), "expected_any": S.grammar(rec, lo, hi, own_refs=False)})
    cases["grammar"] = gr
    cases["lines"] = [{"doc": "M1", "i": i, "local": i + 1, "expected": S.line(docs["M1"], i, i + 1)} for i in range(0, 80, 7)]

    # ---- 4. validate / merge / to_json на синтетических «ответах модели»
    val = []
    for name, rec in recs.items():
        lex = lexical_vectors([u.text for u in rec.utts])
        frags = S.plan(rec, lex, 1500, k=4, alpha=0.0)
        raws, exp_topics, stats_list = [], [], []
        st_total = S.Stats()
        topics = []
        for lo, hi in frags:
            raw = fake_model_output(rec, lo, hi)
            raws.append(raw)
            st = S.Stats()
            try:
                d = json.loads(raw)
            except json.JSONDecodeError:
                d = None
                st.parse_failed += 1
            t = S.validate(d, rec, lo, hi, st, 0.3, True, 0.5) if d else None
            stats_list.append([st.refs_total, st.refs_bad_range, st.refs_bad_speaker, st.relinked, st.items_total, st.items_dropped, st.parse_failed])
            exp_topics.append(S.to_json(rec, [t])["topics"] if t else None)
            if t:
                topics.append(t)
        merged = S.merge(topics, 0.45)
        val.append({"doc": name, "frags": [list(f) for f in frags], "raw": raws, "stats": stats_list, "topics": exp_topics, "merged": S.to_json(rec, merged, "ov")})
    cases["validate"] = val

    # ---- 5. extractive
    ext = []
    for name, rec in recs.items():
        lex = lexical_vectors([u.text for u in rec.utts])
        for k in (3, 4):
            ext.append({"doc": name, "kind": "lexical", "k": k, "expected": S.to_json(rec, X.digest(rec, lex, 1500, k=k, alpha=0.0))})
        ext.append({"doc": name, "kind": "size", "expected": S.to_json(rec, X.digest(rec, None, 1500))})
    cases["extractive"] = ext

    # маркеры решений/задач
    marks = []
    for r in recs.values():
        for u in r.utts:
            marks.append([u.text, bool(X.DECISION.search(u.text)), bool(X.TASK.search(u.text))])
    marks += [[t, bool(X.DECISION.search(t)), bool(X.TASK.search(t))] for t in ["Сдать до пятницы", "К завтра нужно", "как завтра", "фиксируемый срок", "Решили ехать", "ДО КОНЦА недели", "до  пятницы", "по-решили"]]
    cases["marks"] = marks

    OUT.write_text(json.dumps(cases, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(OUT, OUT.stat().st_size // 1024, "KiB; cases:", {k: len(v) for k, v in cases.items()})


def fake_model_output(rec, lo, hi):
    """Правдоподобный ответ модели с ошибками: чужие ссылки, ссылки вне фрагмента, выдуманный текст, битый JSON."""
    n = hi - lo
    if rng.random() < 0.08:
        return '{"title":"Обрыв","points":[{"refs":[1],"text":"текст'
    spk = sorted({rec.utts[i].spk for i in range(lo, hi)})
    points = []
    for s in spk:
        own = [i for i in range(lo, hi) if rec.utts[i].spk == s]
        for _ in range(rng.randint(1, 2)):
            i = rng.choice(own)
            w = rec.utts[i].text.split()
            keep = [x for x in w if rng.random() < 0.7] or w[:2]
            mode = rng.random()
            if mode < 0.15:
                keep = ["совсем", "другое", "про", "космос"]
            elif mode < 0.3:
                keep += ["выдуманное", "слово"]
            r = i - lo + 1
            roll = rng.random()
            refs = [r] if roll < 0.7 else ([rng.randint(1, n)] if roll < 0.85 else [n + rng.randint(1, 3)])
            points.append({"refs": refs, "text": " ".join(keep) + "", "speaker": s if rng.random() < 0.9 else s + 1})
    decisions, tasks = [], []
    for _ in range(rng.randint(0, 2)):
        i = rng.randrange(lo, hi)
        decisions.append({"refs": [i - lo + 1], "text": " ".join(rec.utts[i].text.split()[:6])})
    for _ in range(rng.randint(0, 2)):
        i = rng.randrange(lo, hi)
        tasks.append({"refs": [i - lo + 1, 0], "who": "Говорящий 1" if rng.random() < 0.5 else "", "text": " ".join(rec.utts[i].text.split()[:5]) + " до пятницы"})
    title = rng.choice(["Бюджет на рыбу", "Бюджет рыбы", "Отпуск", " ", "Дежурство по складу", "Дежурство на складе"])
    out = {"title": title, "points": points, "decisions": decisions, "tasks": tasks}
    return json.dumps(out, ensure_ascii=False)


if __name__ == "__main__":
    main()
