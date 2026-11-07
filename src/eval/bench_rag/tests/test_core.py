import numpy as np

from bench_rag import answer as A
from bench_rag import speaker_filter as sf
from bench_rag import summary as S
from bench_rag.bm25 import BM25
from bench_rag.chunker import chunk_record, merge_turns
from bench_rag.data import Record, Utt
from bench_rag.retrieval import Hit, Index
from bench_rag.segment import boundaries


def rec():
    rows = [("speaker_1", 0, 3000, "Давайте обсудим бюджет на рыбу."), ("speaker_2", 3500, 6000, "В июле потратили восемь тысяч."),
            ("speaker_2", 6200, 7000, "400 рублей."), ("speaker_1", 8000, 9000, "А в августе?"), ("speaker_3", 10000, 12000, "Одиннадцать тысяч двести.")]
    return Record("r", [Utt(i, *r) for i, r in enumerate(rows)])


def test_chunker_modes():
    r = rec()
    assert [len(c.utts) for c in chunk_record(r, "utt")] == [1] * 5
    assert len(merge_turns(r.utts)) == 4                      # utterances 1+2 are one speaker, 200 ms apart
    w = chunk_record(r, "win3")
    assert w[0].utts == [0, 1, 2, 3] and w[1].utts[0] == 1 and "Говорящий 2:" in w[0].embed_text
    assert w[0].label.startswith("[00:00, Говорящий 1]")


def test_speaker_parser():
    assert sf.parse("Что говорил говорящий 2 про бюджет?").numbers == {2}
    assert sf.parse("что сказал speaker_3 про отпуск").numbers == {3}
    assert sf.parse("Что сказал второй говорящий?").numbers == {2}
    sq = sf.parse("Что предлагал Василий Петрович про сёмгу?", {"Василий Петрович", "Тихон"})
    assert sq.name_stems and sf.matches(sq, "speaker_1", {"speaker_1": "Василий Петрович"}) and not sf.matches(sq, "speaker_2", {"speaker_2": "Тихон"})
    assert "Василий" not in sq.clean and "сёмгу" in sq.clean
    assert not sf.parse("Что обсуждали про бюджет?").active


def test_bm25_stemming():
    b = BM25(["В июле потратили восемь тысяч на рыбу", "Обсудили отпуск в горах", "Сметаны мало"])
    assert b.scores("что говорили про отпуске")[1] > 0 and b.scores("про сметану")[2] > 0
    assert b.scores("бюджет")[0] == 0


def test_hybrid_speaker_filter_and_fallback():
    r = rec()
    ch = chunk_record(r, "utt")
    v = np.eye(5, dtype=np.float32)
    ix = Index(ch, {"r": r}, v)
    hits = ix.search("говорящий 2 рублей", lambda q: v[2], k=3)
    assert all("speaker_2" in h.chunk.speakers for h in hits)
    hits = ix.search("говорящий 9 рублей", lambda q: v[2], k=3)           # nobody matches -> unfiltered list
    assert hits


def test_segment_boundaries():
    a = np.tile(np.array([1.0, 0, 0], dtype=np.float32), (8, 1))
    b = np.tile(np.array([0, 1.0, 0], dtype=np.float32), (8, 1))
    assert boundaries(np.vstack([a, b]), [100] * 16, k=3, alpha=0.0, min_utts=3, min_chars=200) == [8]


def test_grammar_and_validation():
    r = rec()
    g = S.grammar(r, 0, 5)
    assert 'point1 ::=' in g and 'point3 ::=' in g and 'r2 ::= "2" | "3"' in g          # speaker 2 may cite only its own lines (local 2, 3)
    st = S.Stats()
    d = {"title": "Бюджет", "points": [
        {"speaker": 2, "refs": [2], "text": "В июле потратили восемь тысяч"},                 # ok
        {"speaker": 1, "refs": [2], "text": "В июле потратили восемь тысяч"},                 # line 2 is speaker 2's -> re-linked to a speaker-1 line or dropped
        {"speaker": 3, "refs": [9], "text": "Что-то выдуманное совсем"}],                     # bad ref + unsupported -> dropped
        "decisions": [{"refs": [1], "text": "Космический корабль"}], "tasks": []}
    t = S.validate(d, r, 0, 5, st)
    assert [p.speaker for p in t.points] == [2] and t.points[0].refs == [1]
    assert st.items_dropped >= 3 and not t.decisions and st.refs_bad_range == 1


def test_merge_and_json():
    r = rec()
    t1 = S.Topic("Бюджет на рыбу", 0, 1, [S.Point(1, "Обсуждают бюджет", [0])])
    t2 = S.Topic("Бюджет рыбы", 2, 3, [S.Point(1, "Обсуждают бюджет", [3])])
    m = S.merge([t1, t2])
    assert len(m) == 1 and m[0].end == 3 and len(m[0].points) == 1
    j = S.to_json(r, m)
    assert j["topics"][0]["start_ms"] == 0 and j["topics"][0]["points"][0]["refs"][0]["n"] == 1


def test_answer_citations():
    r = rec()
    ch = chunk_record(r, "utt")
    ctx, cites = A.context([Hit(ch[1], 1.0), Hit(ch[4], 0.5)], {"r": r})
    assert "[00:03, Говорящий 2]" in ctx
    a = A.verify("Потратили 8 тысяч [00:03, Говорящий 2], потом 11 [05:00, Говорящий 3].", cites)
    assert a.bad_cites == 1 and len(a.valid_cites) == 1 and "05:00" not in a.text
