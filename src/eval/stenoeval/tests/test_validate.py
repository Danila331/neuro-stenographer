import json

import pytest

from stenoeval.cli import main
from stenoeval.validate import validate_file, validate_text


def ok(obj, **kw):
    return validate_text(json.dumps(obj, ensure_ascii=False), **kw)


def utt(**over):
    d = {"speaker_id": "speaker_1", "start_ms": 0, "end_ms": 1000, "text": "Привет"}
    d.update(over)
    return d


def test_valid_and_empty():
    assert ok({"transcript": [utt()]}).ok
    assert ok({"transcript": []}).ok


def test_fixtures_valid(fixtures):
    for p in sorted(fixtures.glob("run_*/cat0?.json")) + sorted(fixtures.glob("dataset/ref/*.json")):
        r = validate_file(p)
        assert r.ok, (p, r.errors)


@pytest.mark.parametrize(
    "obj, needle",
    [
        ({"transcript": [utt(start_ms=True)]}, "integer"),
        ({"transcript": [utt(start_ms=1.0)]}, "integer"),
        ({"transcript": [utt(end_ms="5")]}, "integer"),
        ({"transcript": [utt(start_ms=-1)]}, "< 0"),
        ({"transcript": [utt(start_ms=1000, end_ms=1000)]}, "start_ms < end_ms"),
        ({"transcript": [utt(speaker_id="spk1")]}, "speaker_N"),
        ({"transcript": [utt(speaker_id="speaker_0")]}, "speaker_N"),
        ({"transcript": [utt(speaker_id="speaker_2")]}, "first appearance"),
        ({"transcript": [utt(text="")]}, "non-empty"),
        ({"transcript": [utt(text=" Привет")]}, "whitespace"),
        ({"transcript": [utt(text="Привет ")]}, "whitespace"),
        ({"transcript": [utt(text="Привет\nмир")]}, "line break"),
        ({"transcript": [utt(text=5)]}, "string"),
        ({"transcript": [utt(extra=1)]}, "exactly"),
        ({"transcript": [{"speaker_id": "speaker_1", "start_ms": 0, "end_ms": 5}]}, "missing"),
        ({"transcript": [], "meta": {}}, "top-level"),
        ({"items": []}, "transcript"),
        ({"transcript": {}}, "array"),
        ([], "top level"),
        ({"transcript": [utt(start_ms=2000, end_ms=3000), utt(start_ms=0, end_ms=500)]}, "not sorted"),
        ({"transcript": [utt(start_ms=0, end_ms=2000), utt(start_ms=0, end_ms=1000)]}, "not sorted"),
    ],
)
def test_invalid(obj, needle):
    r = ok(obj)
    assert not r.ok
    assert any(needle in e for e in r.errors), r.errors


def test_numbering_by_first_appearance():
    good = {"transcript": [utt(), utt(speaker_id="speaker_2", start_ms=1000, end_ms=2000), utt(start_ms=2000, end_ms=3000)]}
    assert ok(good).ok
    bad = {"transcript": [utt(), utt(speaker_id="speaker_3", start_ms=1000, end_ms=2000)]}
    assert not ok(bad).ok


def test_overlaps_allowed():
    assert ok({"transcript": [utt(end_ms=3000), utt(speaker_id="speaker_2", start_ms=1000, end_ms=2000)]}).ok


def test_duration():
    obj = {"transcript": [utt(end_ms=1000)]}
    assert ok(obj, duration_ms=1000).ok
    r = ok(obj, duration_ms=999)
    assert not r.ok and "duration" in r.errors[0]


def test_duplicate_keys_and_nan():
    r = validate_text('{"transcript": [], "transcript": []}')
    assert not r.ok and "duplicate" in r.errors[0]
    r = validate_text('{"transcript":[{"speaker_id":"speaker_1","start_ms":0,"end_ms":1,"text":"a","text":"b"}]}')
    assert not r.ok and "duplicate" in r.errors[0]
    r = validate_text('{"transcript":[{"speaker_id":"speaker_1","start_ms":NaN,"end_ms":1,"text":"a"}]}')
    assert not r.ok and "NaN" in r.errors[0]
    assert not validate_text("{").ok
    assert not validate_text("").ok


def test_unicode_escape_and_bom(tmp_path):
    escaped = json.dumps({"transcript": [utt()]})  # ensure_ascii=True -> П...
    r = validate_text(escaped)
    assert not r.ok and "\\u" in r.errors[0]
    p = tmp_path / "bom.json"
    p.write_bytes(b"\xef\xbb\xbf" + json.dumps({"transcript": []}).encode())
    assert not validate_file(p).ok
    q = tmp_path / "latin.json"
    q.write_bytes('{"transcript":[]}'.encode("utf-16"))
    assert not validate_file(q).ok
    assert not validate_file(tmp_path / "nope.json").ok


def test_cli_exit_codes(tmp_path, fixtures, capsys):
    good = fixtures / "run_perfect" / "cat01.json"
    assert main(["validate", str(good), "--duration-ms", "14500"]) == 0
    bad = tmp_path / "bad.json"
    bad.write_text('{"transcript":[{"speaker_id":"speaker_1","start_ms":true,"end_ms":2,"text":"x"}]}', encoding="utf-8")
    assert main(["validate", str(good), str(bad)]) == 1
    out = capsys.readouterr().out
    assert "FAIL" in out and "integer" in out
