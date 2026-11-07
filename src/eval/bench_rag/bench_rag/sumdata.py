"""Evaluation documents for B50: composites of cats scenarios (pipeline output, known topic borders) and bench_llm dialogs."""
from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass, field

from .data import DIALOGS, Record, Utt, cats_titles, load_record, map_gold
from .text import terms

COMPOSITES = {
    "M1": ["c01", "c02", "c03", "c04", "c05"],
    "M2": ["c06", "c09", "c13", "c18", "c20"],
    "M3": ["c21", "c23", "c24", "c30", "c32"],
    "M4": ["c33", "c35", "c36", "c39", "c40"],
    "M5": ["c11", "c12", "c14", "c15", "c17"],
    "M6": ["c19", "c22", "c25", "c26", "c27"],
    "M7": ["c28", "c29", "c31", "c34", "c37"],
    "M8": ["c07", "c08", "c10", "c16", "c38"],
}
# (kind, any-of substrings of the item text, who alternatives or None, substring of the reference utterance of the assignee/author or None)
FACTS: dict[str, list[tuple]] = {
    "c02": [("decision", ["багир", "дежурн"], None, None), ("task", ["дежур", "смен"], ["багир"], None)],
    "c03": [("decision", ["сёмг", "семг", "вдво"], None, None), ("task", ["расчёт", "расчет", "пятниц"], None, "Я подготовлю другой расчёт")],
    "c04": [("decision", ["рыбк", "миск"], None, None)],
    "c05": [("decision", ["мыш"], None, None)],
    "c06": [("decision", ["две недел", "испытат"], None, None)],
    "c09": [("decision", ["четыре", "фонд", "4"], None, None)],
    "c13": [("decision", ["письм"], None, None), ("task", ["текст", "составит", "письм"], None, "Я составлю, мяу")],
    "c18": [("decision", ["гор"], None, None), ("decision", ["25", "двадцать пят", "июл"], None, None)],
    "c20": [("decision", ["корзин"], None, None), ("decision", ["когт", "диван"], None, None), ("task", ["ответствен", "порядк"], None, "Принимаю, но с условием")],
    "c21": [("task", ["оформ", "список"], None, "Я составлю список"), ("decision", ["талон", "красн"], None, None)],
    "c23": [("decision", ["бесплат", "котят"], None, None)],
    "c24": [("decision", ["300", "триста", "сбор"], None, None)],
    "c30": [("decision", ["завтрак", "сон"], None, None)],
    "c32": [("decision", ["огон", "огонёк"], None, None)],
    "c33": [("decision", ["пушинк", "дежур"], None, None)],
    "c35": [("decision", ["полудн"], None, None)],
    "c36": [("task", ["заявлен", "подпис", "отправ", "почт"], None, "Несу на почту")],
    "c39": [("decision", ["склад", "поставщик"], None, None)],
}


@dataclass
class GoldTopic:
    title: str
    start: int
    end: int
    keywords: list[str]


@dataclass
class Fact:
    kind: str
    any: list[str]
    who: list[str] | None = None
    who_speaker: int | None = None     # speaker number (pipeline label) of the assignee, when known


@dataclass
class Doc:
    id: str
    rec: Record
    topics: list[GoldTopic]
    facts: list[Fact] = field(default_factory=list)
    claims: list[tuple[int, list[str]]] = field(default_factory=list)   # (speaker, any-of substrings) written by hand


def _idf_keywords(ref_by_sc: dict[str, str], k: int = 8) -> dict[str, list[str]]:
    docs = {c: set(terms(t)) for c, t in ref_by_sc.items()}
    df = Counter(s for d in docs.values() for s in d)
    n = len(docs)
    out = {}
    for c, t in ref_by_sc.items():
        tf = Counter(terms(t))
        sc = {s: f * math.log(n / df[s]) for s, f in tf.items() if len(s) >= 4}
        out[c] = [s for s, _ in sorted(sc.items(), key=lambda x: -x[1])[:k]]
    return out


_KW = None


def keywords() -> dict[str, list[str]]:
    global _KW
    if _KW is None:
        ref = {f"c{i:02d}": " ".join(u.text for u in load_record(f"c{i:02d}", "ref").utts) for i in range(1, 41)}
        _KW = _idf_keywords(ref)
    return _KW


def composite(name: str, kind: str = "asr", gap_ms: int = 5000) -> Doc:
    titles, kws = cats_titles(), keywords()
    utts, topics, facts = [], [], []
    off = 0
    for cid in COMPOSITES[name]:
        asr = load_record(cid, kind)
        ref = load_record(cid, "ref")
        a = len(utts)
        for u in asr.utts:
            utts.append(Utt(len(utts), u.speaker, u.start_ms + off, u.end_ms + off, u.text))
        topics.append(GoldTopic(titles[cid], a, len(utts) - 1, kws[cid]))
        for kind_, anyk, who, src in FACTS.get(cid, []):
            ws = None
            if src:
                hit = [u.idx for u in ref.utts if src in u.text]
                m = map_gold(ref, asr, hit) if hit else []
                ws = asr.utts[m[0]].spk if m else None
            facts.append(Fact(kind_, anyk, who, ws))
        off += asr.duration_ms + gap_ms
    return Doc(name, Record(name, utts), topics, facts)


# ---- hand-written gold for the three bench_llm dialogs --------------------------------------------------------------------------------
DIALOG_TOPICS = {
    "d1_budget": [("Бюджет, витрина", 1, 11, ["витрин", "навес", "бюджет", "этап"]), ("Уборка площади", 12, 99, ["дворник", "уборк", "оплат", "фонар"])],
    "d2_bridge": [("Ремонт моста", 1, 9, ["мост", "доск", "ремонт", "материал"]), ("Дежурный у ручья", 10, 14, ["дежурн", "пушк", "пушок"]), ("Разрешение и объявление", 15, 99, ["разрешен", "объявлен", "ёлк", "елк"])],
    "d3_holiday": [("Сметана для праздника", 1, 4, ["сметан", "ферм", "литр"]), ("Безопасность", 5, 7, ["доброволец", "добровольц", "жилет", "безопасн"]), ("Музыка и расписание", 8, 11, ["хор", "оркестр", "расписан", "конкурс"]), ("Дождь, запасной вариант", 12, 99, ["дожд", "библиотек"])],
}
DIALOG_CLAIMS = {
    "d1_budget": [(2, ["триста", "300", "навес"]), (3, ["дорого", "180", "сто восемьдесят"]), (3, ["два этапа", "этап"]), (2, ["барсик", "доставк", "свяж"]),
                  (3, ["размер", "измер"]), (2, ["дворник", "пятьдесят", "50"]), (3, ["мало", "встают"]), (1, ["семидесят", "70"]), (2, ["пять тысяч", "5000", "5 тысяч"]),
                  (1, ["тишк", "ответствен"]), (2, ["фонар"])],
    "d2_bridge": [(2, ["доск", "гнил", "перил"]), (3, ["котят", "школ", "мельниц"]), (2, ["четыре", "4 дн", "четверг"]), (3, ["материал", "половин"]), (2, ["сорок", "40", "заказ"]),
                  (1, ["дорожн", "пятьдесят"]), (3, ["дежурн", "тропа", "тропе"]), (3, ["пушк", "пушок"]), (2, ["разрешен", "ёлк", "елк"]), (1, ["разрешен", "скан"]), (3, ["объявлен"])],
    "d3_holiday": [(2, ["план", "открыт", "конкурс"]), (3, ["сто литров", "100"]), (2, ["ферм", "восемьдесят", "80"]), (3, ["доброволец", "добровольц"]), (2, ["жилет"]),
                   (3, ["хор", "оркестр", "пять тысяч"]), (3, ["дожд", "библиотек"]), (3, ["сто пятьдесят", "150", "не поместит"]), (2, ["два захода", "утром и вечером", "два раза"])],
}


def dialog(name: str) -> Doc:
    import json

    from .data import HERE
    rows, utts, t = [], [], 0
    for ln in (DIALOGS / f"{name}.txt").read_text(encoding="utf-8").splitlines():
        m = re.match(r"(\d+): (.*)", ln)
        if m:
            dur = int(max(2000, len(m[2].split()) * 420))
            utts.append(Utt(len(utts), f"speaker_{m[1]}", t, t + dur, m[2]))
            t += dur + 400
    rec = Record(name, utts)
    topics = [GoldTopic(ti, a - 1, min(b, len(utts)) - 1, kw) for ti, a, b, kw in DIALOG_TOPICS[name]]
    gold = json.loads((HERE.parent / "bench_llm" / "data" / "protocol_gold.json").read_text(encoding="utf-8"))[name]
    facts = [Fact(i["kind"], i["any"]) for i in gold["items"]]
    return Doc(name, rec, topics, facts, DIALOG_CLAIMS[name])


def all_docs(kind: str = "asr") -> list[Doc]:
    return [composite(n, kind) for n in COMPOSITES] + [dialog(n) for n in DIALOG_TOPICS]
