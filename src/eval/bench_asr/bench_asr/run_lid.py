"""Определение языка (ru/en) по коротким фрагментам речи: sherpa-onnx SpokenLanguageIdentification (whisper-based).

python -m bench_asr.run_lid --lid whisper_tiny --ru <ru_dataset> --en <en_dataset> --secs 5,10 --threads 2 --out results/lid.jsonl
Фрагмент = первые N секунд речи (по VAD) файла. Метрика: accuracy на ru и en, матрица ошибок, время на вызов, пик RAM.
"""
from __future__ import annotations
import argparse, json, time, glob, resource, sys, collections
import numpy as np
import sherpa_onnx
from .engines import MODELS, _pick
from .data import load_dataset
from .vad import vad_segments, SR

LID_MODELS = {"whisper_tiny": "sherpa-onnx-whisper-tiny", "whisper_base": "sherpa-onnx-whisper-base",
              "whisper_small": "sherpa-onnx-whisper-small"}


def peak_mb():
    r = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return r / 2**20 if sys.platform == "darwin" else r / 1024


def make_lid(name, precision, threads):
    d = MODELS / LID_MODELS[name]
    cfg = sherpa_onnx.SpokenLanguageIdentificationConfig(
        whisper=sherpa_onnx.SpokenLanguageIdentificationWhisperConfig(
            encoder=_pick(d, "*encoder*.onnx", precision), decoder=_pick(d, "*decoder*.onnx", precision)),
        num_threads=threads, debug=False, provider="cpu")
    return sherpa_onnx.SpokenLanguageIdentification(cfg), d


def first_speech(x, secs):
    segs = vad_segments(x, threshold=0.45)
    if not segs:
        return x[: int(secs * SR)]
    a = segs[0][0]
    return x[a: a + int(secs * SR)]


def mixed_eval(a, slid):
    import csv, collections
    rows = list(csv.DictReader(open(f"{a.mixed}/manifest.csv", encoding="utf-8")))
    items = {it.id: it for it in load_dataset(a.mixed)}
    res = []
    for secs in map(float, a.secs.split(",")):
        conf = collections.Counter(); by_cond = collections.defaultdict(lambda: [0, 0]); first10 = [0, 0]
        for r in rows:
            x = items[r["id"]].load_audio(); segs = json.loads(r["lang_segments"])
            for sg in segs:
                if sg["end_ms"] - sg["start_ms"] < 2000:
                    continue
                a0 = int(sg["start_ms"] * SR / 1000); y = x[a0: a0 + int(min(secs, (sg["end_ms"] - sg["start_ms"]) / 1000) * SR)]
                st = slid.create_stream(); st.accept_waveform(SR, y); pred = slid.compute(st)
                ok = (pred == "en") == (sg["lang"] == "en")        # бинарно: en против «не en»
                conf[(sg["lang"], pred == "en")] += 1; by_cond[r["condition"]][0] += ok; by_cond[r["condition"]][1] += 1
            # режим приложения: первые 10 с речи файла -> язык файла = язык этой выборки; метка — доминирующий язык в первых 10 с
            seg0 = [sg for sg in segs if sg["start_ms"] < 10000]
            dur = collections.Counter()
            for sg in seg0:
                dur[sg["lang"]] += min(sg["end_ms"], 10000) - sg["start_ms"]
            truth = dur.most_common(1)[0][0] if dur else "ru"
            st = slid.create_stream(); st.accept_waveform(SR, first_speech(x, 12)[: 10 * SR]); pred = slid.compute(st)
            first10[0] += (pred == "en") == (truth == "en"); first10[1] += 1
        row = {"lid": a.lid, "set": "ru-en-mixed", "secs": secs, "segment_conf(ru/en->pred_en)": {f"{k[0]}->{'en' if k[1] else 'not_en'}": v for k, v in conf.items()},
               "segment_acc_by_condition": {k: v[0] / v[1] for k, v in by_cond.items()}, "file_first10s_acc": first10[0] / first10[1], "n_files": first10[1]}
        print(json.dumps(row, ensure_ascii=False), flush=True)
        with open(a.out, "a", encoding="utf-8") as f:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lid", default="whisper_tiny"); ap.add_argument("--precision", default="int8")
    ap.add_argument("--ru", default=None); ap.add_argument("--en", default=None)
    ap.add_argument("--secs", default="5,10"); ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--limit", type=int, default=100); ap.add_argument("--out", required=True)
    ap.add_argument("--mixed", default=None, help="ru-en-mixed: оценка по lang_segments (сегменты >= 2 с) и по первым 10 с файла")
    a = ap.parse_args()
    slid, d = make_lid(a.lid, a.precision, a.threads)
    if a.mixed:
        return mixed_eval(a, slid)
    sets = {"ru": load_dataset(a.ru, limit=a.limit), "en": load_dataset(a.en, limit=a.limit)}
    clips = {l: [first_speech(it.load_audio(), 12) for it in items] for l, items in sets.items()}
    for secs in map(float, a.secs.split(",")):
        conf = collections.Counter(); times = []
        for lang, xs in clips.items():
            for x in xs:
                x = x[: int(secs * SR)]
                t0 = time.perf_counter()
                st = slid.create_stream(); st.accept_waveform(SR, x)
                pred = slid.compute(st)
                times.append(time.perf_counter() - t0)
                conf[(lang, pred)] += 1
        acc = {l: conf[(l, l)] / max(sum(v for (k, _), v in conf.items() if k == l), 1) for l in clips}
        row = {"lid": a.lid, "precision": a.precision, "secs": secs, "threads": a.threads, "acc_ru": acc["ru"], "acc_en": acc["en"],
               "confusion": {f"{k[0]}->{k[1]}": v for k, v in conf.items()}, "ms_per_call_mean": 1000 * np.mean(times),
               "ms_per_call_median": 1000 * np.median(times), "peak_rss_mb": peak_mb()}
        print(json.dumps(row, ensure_ascii=False), flush=True)
        with open(a.out, "a", encoding="utf-8") as f:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
