"""JSON-кейсы паритета для `ru.neurosteno.core.search` (Kotlin) ПРЯМО из эталона `src/eval/bench_rag/bench_rag`:
нарезка на окна (chunker), BM25, разбор говорящего (speaker_filter), гибридный поиск с RRF (retrieval.Index.search), контекст ответа
и проверка ссылок (answer). Выход: core/src/test/resources/search_cases.json.

  src/eval/bench_rag/.venv/bin/python src/android/scripts/gen_search_cases.py
Данные: корпус cats (эталонные и распознанные расшифровки из $ROOT/.cache) -- только тексты и времена; векторы случайные (детерминированно).
"""
import json
import random
import sys
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "src/eval/bench_rag"))
from bench_rag import answer as A  # noqa: E402
from bench_rag import speaker_filter as sf  # noqa: E402
from bench_rag.bm25 import BM25  # noqa: E402
from bench_rag.chunker import chunk_record  # noqa: E402
from bench_rag.data import Record, Utt, load_record  # noqa: E402
from bench_rag.evalset import build_questions  # noqa: E402
from bench_rag.retrieval import Hit, Index  # noqa: E402

OUT = REPO / "src/android/core/src/test/resources/search_cases.json"
DIM = 12


def rec_json(r: Record):
    return {"id": r.id, "title": r.title, "names": r.names,
            "utts": [{"idx": u.idx, "speaker": u.speaker, "start_ms": u.start_ms, "end_ms": u.end_ms, "text": u.text} for u in r.utts]}


def chunk_json(c):
    return {"cid": c.cid, "record": c.record, "utts": c.utts, "speakers": list(c.speakers), "start_ms": c.start_ms, "end_ms": c.end_ms,
            "text": c.text, "embed_text": c.embed_text}


def synthetic_records():
    """Крайние случаи нарезки: паузы, длинные реплики, смена говорящего, эмодзи (кодовые точки), одна реплика."""
    rows = [("speaker_1", 0, 3000, "Давайте обсудим бюджет на рыбу."), ("speaker_1", 3500, 6000, "В июле потратили восемь тысяч."),
            ("speaker_1", 9000, 9500, "Через пять секунд после паузы."), ("speaker_2", 9600, 11000, "Ответ второго 😀 говорящего."),
            ("speaker_2", 11200, 12000, "400 рублей."), ("speaker_1", 12100, 12500, "А в августе?"),
            ("speaker_1", 12600, 25000, "Очень длинная реплика " + "слово " * 70), ("speaker_1", 25100, 26000, "хвост"),
            ("speaker_3", 26500, 28000, "Одиннадцать тысяч двести.")]
    r1 = Record("syn1", [Utt(i, *r) for i, r in enumerate(rows)], {"speaker_1": "Василий Петрович", "speaker_2": "Тихон"}, "Синтетика 1")
    r2 = Record("syn2", [Utt(0, "speaker_2", 0, 1000, "Единственная реплика про сметану.")], {}, "Синтетика 2")
    r3 = Record("syn3", [], {}, "Пустая")
    return [r1, r2, r3]


def unit_vec(rng, n, dim):
    x = rng.normal(size=(n, dim)).astype(np.float32)
    x /= np.linalg.norm(x, axis=1, keepdims=True)
    return x


def main():
    rng = np.random.default_rng(51)
    rnd = random.Random(51)
    recs: dict[str, Record] = {}
    for rid in ["c01", "c02", "c03", "c05", "c09", "c11"]:
        r = load_record(rid, "asr")
        recs[rid] = r
    for rid in ["c13", "c18"]:
        recs[rid] = load_record(rid, "ref")
    names = {"c02": {"speaker_1": "Василий", "speaker_2": "Маркиз", "speaker_3": "Багира"}, "c03": {"speaker_1": "Василий Петрович", "speaker_2": "Тихон"},
             "c09": {"speaker_1": "Мурзик", "speaker_2": "Барсик", "speaker_3": "Леся"}}
    for rid, nm in names.items():
        recs[rid].names = dict(nm)
    for r in recs.values():
        r.title = f"Запись {r.id}"
    syn = synthetic_records()
    cases: dict = {"chunk": [], "bm25": [], "speaker": [], "search": [], "answer": [], "meta": {"dim": DIM}}

    # ---- chunker --------------------------------------------------------------------------------------------------------------------
    for r in list(recs.values())[:5] + syn:
        for mode in ("utt", "turn", "win3s1", "win3", "win2s2", "win4"):
            cases["chunk"].append({"mode": mode, "record": rec_json(r), "start_cid": 100, "chunks": [chunk_json(c) for c in chunk_record(r, mode, 100)]})

    # ---- bm25 -------------------------------------------------------------------------------------------------------------------------
    chunks_all = []
    for r in recs.values():
        chunks_all += chunk_record(r, "win3s1", len(chunks_all))
    docs = [c.text for c in chunks_all]
    queries = ["сколько мышей на складе", "что говорили про отпуск в горах", "миска будет синяя", "рыбу за квартал бюджет", "пшено молоко литр",
               "соседского пса жалоба", "ёлка ёж", "что решили по ремонту крыши", "о", "123 квартира", "SPEAKER обсуждали сметану", "ничего подобного нет xyzzy"]
    for mode in ("snow5", "snow", "trunc5", "none"):
        b = BM25(docs, mode=mode)
        cases["bm25"].append({"mode": mode, "docs": docs, "queries": [{"q": q, "scores": [float(x) for x in b.scores(q)]} for q in queries], "avgdl": float(b.avgdl)})

    # ---- speaker parser ---------------------------------------------------------------------------------------------------------------
    qs = ["Что говорил говорящий 2 про бюджет?", "что сказал speaker_3 про отпуск", "Что сказал второй говорящий?", "Спикер номер три предложил", "говорящий № 1 жаловался",
          "Что предлагал Василий Петрович про сёмгу?", "Что Тихон говорил про сентябрь?", "Что обсуждали про бюджет?", "что сказали первый спикер и второй участник",
          "голос пять рассказал", "Скажи что говорила Багира про мышей", "собеседник два и говорящий 4", "SPEAKER-7 что-то", "Маркиз и Василий о складе", "ничего"]
    nameset = [None, {"Василий Петрович", "Тихон"}, {"Василий", "Маркиз", "Багира"}, {"Мурзик", "Барсик", "Леся"}]
    for q in qs:
        for ns in nameset:
            sq = sf.parse(q, ns)
            row = {"query": q, "names": sorted(ns) if ns else None, "clean": sq.clean, "numbers": sorted(sq.numbers), "name_stems": sq.name_stems}
            rn = {"speaker_1": "Василий Петрович", "speaker_2": "Тихон", "speaker_3": "Багира"}
            row["matches"] = [{"speaker": s, "names": rn, "result": sf.matches(sq, s, rn)} for s in ("speaker_1", "speaker_2", "speaker_3", "speaker_4")]
            cases["speaker"].append(row)

    # ---- hybrid search ----------------------------------------------------------------------------------------------------------------
    allrecs = dict(recs)
    for r in syn[:2]:
        allrecs[r.id] = r
    chunks = []
    for r in allrecs.values():
        chunks += chunk_record(r, "win3s1", len(chunks))
    vecs = unit_vec(rng, len(chunks), DIM)
    ix = Index(chunks, allrecs, vecs, stem_mode="snow5")
    cases["search_index"] = {"records": [rec_json(r) for r in allrecs.values()], "chunks": [chunk_json(c) for c in chunks],
                             "vectors": [[float(x) for x in v] for v in vecs], "dim": DIM}
    _, qset = build_questions("asr")
    texts = [q.text for q in qset if q.id in ("s01", "s02", "s03", "n01", "n02", "n04", "t01", "t02", "t03", "t05", "t10", "h01", "h02", "h03", "h04")]
    texts += ["Что говорил говорящий 2 про сметану?", "что сказал первый говорящий про рыбу", "что говорил Тихон про бюджет", "что предлагал Василий Петрович",
              "говорящий 9 рублей", "про рыбу", "бюджет", "xyzzy такого нет", "Багира мыши склад"]
    for t in texts:
        for record in (None, "c03", "c02"):
            if rnd.random() < 0.55:
                continue
            qv = unit_vec(rng, 1, DIM)[0]
            for mode in ("soft", "hard", "off"):
                for bm25, dense in ((True, True), (True, False), (False, True)):
                    hits = ix.search(t, lambda q: qv, k=10 if mode == "soft" else 5, record=record, speaker_mode=mode, bm25=bm25, dense=dense)
                    cases["search"].append({"query": t, "qvec": [float(x) for x in qv], "record": record, "mode": mode, "bm25": bm25, "dense": dense,
                                            "k": 10 if mode == "soft" else 5, "hits": [{"cid": h.chunk.cid, "score": h.score} for h in hits]})

    # ---- answer ---------------------------------------------------------------------------------------------------------------------
    for qi, picks in enumerate(([0, 3, 7], [2], [5, 6, 40, 41], [1, 2, 3])):
        hs = [Hit(chunks[i % len(chunks)], 1.0) for i in picks]
        ctx, cites = A.context(hs, allrecs, 3200 if qi != 2 else 400)
        ver = []
        base = [c for c in cites]
        samples = []
        if base:
            rid, t, sp = base[0]
            mm = f"[{t // 60:02d}:{t % 60:02d}, Говорящий {sp.split('_')[1]}]"
            mm1 = f"[{(t + 1) // 60:02d}:{(t + 1) % 60:02d}, Говорящий {sp.split('_')[1]}]"
            samples += [f"Об этом говорили {mm}, потом ещё {mm1} .", f"Неверная ссылка [99:59, Говорящий 7] и {mm}.", "Просто текст без ссылок .",
                        "В найденных фрагментах этого нет.", f"{mm} , {mm} ; [00:00, Говорящий  02]"]
        for s in samples:
            v = A.verify(s, cites)
            ver.append({"text": s, "out": v.text, "valid": [list(c) for c in v.valid_cites], "bad": v.bad_cites, "refused": v.refused})
        cases["answer"].append({"hits": [h.chunk.cid for h in hs], "max_chars": 3200 if qi != 2 else 400, "context": ctx, "cites": [list(c) for c in cites], "verify": ver})

    OUT.write_text(json.dumps(cases, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(OUT, OUT.stat().st_size // 1024, "KiB;", {k: len(v) for k, v in cases.items() if isinstance(v, list)})


if __name__ == "__main__":
    main()
