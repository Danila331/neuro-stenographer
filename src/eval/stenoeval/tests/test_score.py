import csv
import json
import shutil

import pytest

from conftest import write_json
from stenoeval.cli import main
from stenoeval.results import COLUMNS, read_csv, summarize, unpack_notes, pack_notes
from stenoeval.score import ScoreOptions, details_json, run_score


def score(fixtures, run, **kw):
    return run_score(ScoreOptions(dataset_root=fixtures / "dataset", run_dir=fixtures / run, commit="deadbeef", **kw))


def test_perfect_run(fixtures):
    scores, warnings = score(fixtures, "run_perfect")
    assert not warnings
    assert len(scores) == 4
    for s in scores:
        r = s.row
        assert list(r) == COLUMNS
        assert r["wer_raw"] == r["wer_norm"] == r["cpwer_norm"] == r["der"] == r["der_collar"] == 0
        assert r["rtf"] == 0.1 and r["tier"] == "lite" and r["config_hash"] == "abc123"


def test_noisy_run_values(fixtures):
    scores, _ = score(fixtures, "run_noisy")
    by = {s.item.id: s for s in scores}
    assert by["cat01"].row["wer_norm"] == 0
    assert by["cat01"].row["wer_raw"] > 0.5                    # no punctuation / capitals
    assert by["cat02"].row["num_speakers_ref"] == 3 and by["cat02"].row["num_speakers_hyp"] == 2
    assert by["cat02"].row["cpwer_norm"] == pytest.approx(12 / 29, abs=1e-4)
    assert by["cat02"].row["wer_norm"] == 0                    # plain WER ignores speaker errors
    assert by["cat03"].metrics.wer["norm_num"].errors == 0     # "25" == "двадцать пять" only in norm_num
    assert by["cat03"].metrics.wer["norm"].errors > 0
    assert by["cat04"].row["wer_norm"] == 1.0                  # empty reference, hallucinated word
    assert by["cat04"].row["der"] == ""                        # undefined
    assert by["cat04"].row["ts_start_mae_ms"] == ""
    assert 80 <= by["cat01"].row["ts_start_mae_ms"] <= 120
    assert by["cat01"].row["rtf"] == 0.5


def test_swapped_and_edge_hypotheses(fixtures, tmp_path):
    run = tmp_path / "run"
    run.mkdir()
    ds = fixtures / "dataset"
    # cat01: speaker ids permuted -> cpWER/DER must not care
    ref = json.loads((ds / "ref" / "cat01.json").read_text(encoding="utf-8"))["transcript"]
    perm = {"speaker_1": "speaker_2", "speaker_2": "speaker_1"}
    write_json(run / "cat01.json", [(perm[u["speaker_id"]], u["start_ms"], u["end_ms"], u["text"]) for u in ref])
    write_json(run / "cat02.json", [])                         # empty transcript
    # cat03 hypothesis is a single speaker; cat04 file missing
    shutil.copy(ds / "ref" / "cat03.json", run / "cat03.json")
    scores, warnings = run_score(ScoreOptions(dataset_root=ds, run_dir=run, commit="x"))
    by = {s.item.id: s for s in scores}
    assert by["cat01"].row["wer_norm"] == 0 and by["cat01"].row["cpwer_norm"] == 0 and by["cat01"].row["der"] == 0
    assert by["cat02"].row["wer_norm"] == 1.0 and by["cat02"].row["der"] == 1.0
    assert by["cat04"].flags == ["missing_hyp"]
    assert by["cat04"].row["wer_norm"] == 0.0                  # empty ref + empty (missing) hyp
    assert any("no hypothesis" in w for w in warnings)
    only, _ = run_score(ScoreOptions(dataset_root=ds, run_dir=run, commit="x", only_present=True))
    assert {s.item.id for s in only} == {"cat01", "cat02", "cat03"}


def test_invalid_hypothesis_is_flagged_but_scored(fixtures, tmp_path):
    run = tmp_path / "run"
    run.mkdir()
    (run / "cat01.json").write_text('{"transcript":[{"speaker_id":"speaker_1","start_ms":0,"end_ms":900000,"text":"добрый день"}]}', encoding="utf-8")
    (run / "cat02.json").write_text("not json", encoding="utf-8")
    scores, warnings = run_score(ScoreOptions(dataset_root=fixtures / "dataset", run_dir=run, ids=["cat01", "cat02"], commit="x"))
    by = {s.item.id: s for s in scores}
    assert "invalid_hyp" in by["cat01"].flags and "unparseable_hyp" in by["cat02"].flags
    assert by["cat02"].row["wer_norm"] == 1.0
    assert len(warnings) >= 2


def test_summary_micro_average_and_conditions(fixtures):
    scores, _ = score(fixtures, "run_noisy")
    rows = [{k: str(v) for k, v in s.row.items()} for s in scores]
    s = summarize(rows)
    assert s["wer_norm_exact"]
    errs = sum(x.metrics.wer["norm"].errors for x in scores)
    words = sum(x.metrics.wer["norm"].ref_words for x in scores)
    assert s["wer_norm"] == pytest.approx(errs / words)
    assert s["wer_norm"] != pytest.approx(s["wer_norm_macro"])  # micro != macro here (cat04 has 0 words)
    assert s["spk_acc"] == 0.5
    assert s["rtf_total"] == pytest.approx(0.5)


def test_notes_roundtrip():
    n = pack_notes("my note [x]", {"a": 1, "b": 0.12345678, "c": None})
    free, kv = unpack_notes(n)
    assert free == "my note [x]" and kv == {"a": "1", "b": "0.1235"}
    assert unpack_notes("plain") == ("plain", {})


def test_cli_end_to_end(fixtures, tmp_path, capsys):
    csv_path = tmp_path / "results.csv"
    ds = str(fixtures / "dataset")
    for run, rid in (("run_perfect", "a"), ("run_noisy", "b")):
        rc = main(["score", "--dataset", ds, "--run", str(fixtures / run), "--run-id", rid, "--tier", "lite",
                   "--platform", "android", "--device", "Pixel", "--notes", "hello", "--csv", str(csv_path),
                   "--json-out", str(tmp_path / f"{rid}.json")])
        assert rc == 0
    out = capsys.readouterr().out
    assert "WER norm" in out and "overlap" in out
    rows = read_csv(csv_path)
    assert len(rows) == 8
    with csv_path.open(encoding="utf-8") as f:
        assert next(csv.reader(f)) == COLUMNS
    assert {r["platform"] for r in rows} == {"android"} and rows[0]["device"] == "Pixel"
    assert rows[0]["commit"] == "deadbeef" or len(rows[0]["commit"]) >= 7
    assert json.loads((tmp_path / "b.json").read_text(encoding="utf-8"))[0]["file_id"] == "cat01"

    # duplicate run_id is refused, --replace overwrites
    with pytest.raises(SystemExit):
        main(["score", "--dataset", ds, "--run", str(fixtures / "run_noisy"), "--run-id", "b", "--csv", str(csv_path)])
    assert main(["score", "--dataset", ds, "--run", str(fixtures / "run_noisy"), "--run-id", "b", "--csv", str(csv_path), "--replace"]) == 0
    assert len(read_csv(csv_path)) == 8

    capsys.readouterr()
    assert main(["compare", str(csv_path), "--run-ids", "a,b"]) == 0
    text = capsys.readouterr().out
    assert "REGRESSION" in text and "WER norm" in text
    assert main(["compare", str(csv_path), "--run-ids", "a,b", "--fail-on-regression"]) == 1
    assert main(["compare", str(csv_path), "--run-ids", "b,a", "--fail-on-regression"]) == 0   # improvement
    assert main(["compare", str(csv_path), "--run-ids", "a,a", "--fail-on-regression"]) == 0
    with pytest.raises(SystemExit):
        main(["compare", str(csv_path), "--run-ids", "a,zzz"])

    md = tmp_path / "out" / "report.md"
    assert main(["report", str(csv_path), "--out", str(md)]) == 0
    t = md.read_text(encoding="utf-8")
    assert "## Сводка по прогонам" in t and "| a |" in t and "cat03" in t


def test_split_filter(fixtures):
    with pytest.raises(SystemExit):
        score(fixtures, "run_perfect", split="test")       # no test files in the fixture -> nothing to score
    scores, _ = score(fixtures, "run_perfect", split="dev", ids=["cat01"])
    assert [s.item.id for s in scores] == ["cat01"]


def test_der_collar_column_is_nist(fixtures):
    scores, _ = score(fixtures, "run_noisy")
    for s in scores:
        d = s.metrics.der
        assert s.row["der_collar"] == (round(d["nist"].der, 5) if d["nist"].der is not None else "")
    cat02 = {s.item.id: s for s in scores}["cat02"]
    assert cat02.metrics.der["c025"].der != cat02.metrics.der["nist"].der   # collar=0.25 kept only in details
    assert cat02.row["der_collar"] < cat02.row["der"]
    d = details_json(scores)[1]["der"]
    assert set(d) == {"c0", "c025", "nist"}
    assert "dtot_c=" in cat02.row["notes"]


def test_full_json_word_times(fixtures, tmp_path):
    ds = fixtures / "dataset"
    run = tmp_path / "run"
    shutil.copytree(fixtures / "run_noisy", run)
    # no .full.json -> proportional split
    scores, _ = run_score(ScoreOptions(dataset_root=ds, run_dir=run, commit="x", ids=["cat01"]))
    assert "ts=utt" in scores[0].row["notes"] and scores[0].ts_source == "utt"
    prop_mae = scores[0].row["ts_start_mae_ms"]
    # exact word times of the reference (spread evenly) -> MAE ~0 and ts=words
    ref = json.loads((ds / "ref" / "cat01.json").read_text(encoding="utf-8"))["transcript"]
    utts = []
    for u in ref:
        ws = u["text"].split()
        step = (u["end_ms"] - u["start_ms"]) / len(ws)
        words = [{"text": w, "start_ms": round(u["start_ms"] + i * step), "end_ms": round(u["start_ms"] + (i + 1) * step)} for i, w in enumerate(ws)]
        utts.append({**u, "words": words})
    (run / "cat01.full.json").write_text(json.dumps({"utterances": utts, "tier": "lite"}, ensure_ascii=False), encoding="utf-8")
    scores, warnings = run_score(ScoreOptions(dataset_root=ds, run_dir=run, commit="x", ids=["cat01"]))
    s = scores[0]
    assert "ts=words" in s.row["notes"] and s.ts_source == "words"
    assert s.row["ts_start_mae_ms"] < prop_mae and s.row["ts_start_mae_ms"] <= 2 and s.row["ts_end_mae_ms"] <= 2
    assert s.row["wer_norm"] == 0                      # WER/DER still come from cat01.json
    # broken .full.json -> warning + fallback
    (run / "cat01.full.json").write_text("{oops", encoding="utf-8")
    scores, warnings = run_score(ScoreOptions(dataset_root=ds, run_dir=run, commit="x", ids=["cat01"]))
    assert "ts=utt" in scores[0].row["notes"] and any("full.json" in w for w in warnings)


def test_punct_wer_in_notes_summary_and_json(fixtures, tmp_path, capsys):
    scores, _ = score(fixtures, "run_noisy")
    by = {s.item.id: s for s in scores}
    notes = by["cat01"].row["notes"]
    for key in ("refw_p=", "werr_p=", "refw_ps=", "werr_ps=", "refw_pl=", "werr_pl="):
        assert key in notes
    assert by["cat01"].metrics.wer["punct_lc"].errors > 0          # hyp has no punctuation
    assert by["cat01"].metrics.wer["norm"].errors == 0
    rows = [{k: str(v) for k, v in s.row.items()} for s in scores]
    sm = summarize(rows)
    errs = sum(x.metrics.wer["punct"].errors for x in scores)
    words = sum(x.metrics.wer["punct"].ref_words for x in scores)
    assert sm["wer_punct"] == pytest.approx(errs / words)
    assert sm["wer_punct"] > sm["wer_norm"]
    assert sm["wer_punct_sep"] is not None and sm["wer_punct_lc"] is not None
    # JSON details carry all modes
    d = details_json(scores)[0]
    assert {"raw", "norm", "norm_hy", "norm_num", "punct", "punct_sep", "punct_lc"} <= set(d["wer"])

    # CLI: headline, compare and report show the punct columns
    csv_path = tmp_path / "r.csv"
    ds = str(fixtures / "dataset")
    for run, rid in (("run_perfect", "a"), ("run_noisy", "b")):
        assert main(["score", "--dataset", ds, "--run", str(fixtures / run), "--run-id", rid, "--csv", str(csv_path)]) == 0
    out = capsys.readouterr().out
    assert "MAIN (micro): WER norm" in out and "WER punct" in out
    main(["compare", str(csv_path), "--run-ids", "a,b"])
    out = capsys.readouterr().out
    assert "WER punct" in out and "WER p-sep" in out and "WER p-lc" in out
    main(["report", str(csv_path), "--out", str(tmp_path / "r.md")])
    assert "WER punct" in (tmp_path / "r.md").read_text(encoding="utf-8")


def test_punct_regression_detected(fixtures, tmp_path):
    """A run that only gets worse in punctuation (norm WER equal) must be flagged by compare."""
    run = tmp_path / "run"
    shutil.copytree(fixtures / "run_perfect", run)
    p = run / "cat01.json"
    obj = json.loads(p.read_text(encoding="utf-8"))
    for u in obj["transcript"]:
        u["text"] = u["text"].replace(",", "").replace("!", "").replace(".", "")
    p.write_text(json.dumps(obj, ensure_ascii=False), encoding="utf-8")
    csv_path = tmp_path / "r.csv"
    ds = str(fixtures / "dataset")
    main(["score", "--dataset", ds, "--run", str(fixtures / "run_perfect"), "--run-id", "a", "--csv", str(csv_path)])
    main(["score", "--dataset", ds, "--run", str(run), "--run-id", "b", "--csv", str(csv_path)])
    assert main(["compare", str(csv_path), "--run-ids", "a,b", "--fail-on-regression"]) == 1
