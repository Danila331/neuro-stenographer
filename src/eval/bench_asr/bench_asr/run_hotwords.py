"""Контекстное смещение (hotwords) для transducer-моделей sherpa-onnx: modified_beam_search + hotwords_file.

python -m bench_asr.run_hotwords --model gigaam3_rnnt --cats <ru-dialog-tts-cats> --general <fleurs_root> --scores 0.5,1,1.5,2,3 --out results/hotwords.jsonl

Два набора:
  cats    — диалоги «котиков» (в них встречаются Котовице, протокол, заседание, мяу и имена): WER и recall хотвордов
  general — общие фразы (FLEURS/ru-asr-dev), где хотвордов почти нет: проверка «не ухудшает» (WER и ложные срабатывания)
Списки: L1 — 4 слова из условия (котовице, протокол, заседание, мяу); L2 — L1 + имена котов и термины совещания.
Декодирование: greedy_search (база), modified_beam_search без хотвордов, modified_beam_search + хотворды (score s).
Диалоги режутся как в приложении: Silero VAD -> чанки до 25 с.
"""
from __future__ import annotations
import argparse, json, tempfile, collections
from pathlib import Path
from .engines import Engine
from .registry import REGISTRY
from .data import load_dataset
from .textnorm import norm, wer_counts
from .vad import vad_segments, pack_chunks, SR

L1 = ["котовице", "протокол", "заседание", "мяу"]
L2 = L1 + ["мурзик", "барсик", "рыжик", "пушинка", "багира", "тихон", "маркиз", "мурлыкина", "председатель",
           "повестка", "голосование", "секретарь"]


def hw_file(words, kind, path):
    Path(path).write_text("\n".join(" ".join(w) if kind == "nemo_transducer" else w for w in words) + "\n", encoding="utf-8")


def decode_item(eng, it, chunked):
    x = it.load_audio()
    if not chunked:
        return eng.decode(x).text
    parts = [eng.decode(x[a:b]).text for a, b in pack_chunks(vad_segments(x), 25.0, x=x)]
    return " ".join(p for p in parts if p)


def evaluate(eng, items, words, chunked):
    S = [0, 0, 0, 0]; found = tot = fa = 0
    hs = set(words)
    for it in items:
        h = decode_item(eng, it, chunked)
        c = wer_counts(it.ref_text, h, "norm"); S = [a + b for a, b in zip(S, c)]
        rc, hc = collections.Counter(norm(it.ref_text).split()), collections.Counter(norm(h).split())
        for w in hs:
            tot += rc[w]; found += min(rc[w], hc[w]); fa += max(0, hc[w] - rc[w])
    return {"wer": (S[0] + S[1] + S[2]) / max(S[3], 1), "N": S[3], "hot_total": tot, "hot_found": found, "false_alarm": fa}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True); ap.add_argument("--cats", required=True); ap.add_argument("--general", required=True)
    ap.add_argument("--general-n", type=int, default=60)
    ap.add_argument("--scores", default="0.5,1,1.5,2,3"); ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--max-active-paths", type=int, default=4); ap.add_argument("--lists", default="L1,L2")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    spec = REGISTRY[a.model]
    cats = load_dataset(a.cats, split="dev"); gen = load_dataset(a.general, limit=a.general_n)
    tmp = Path(tempfile.mkdtemp())
    rows = []

    def run(label, eng, lst, score):
        r = {"model": a.model, "decoding": label, "list": lst, "score": score,
             "cats": evaluate(eng, cats, L2, True), "general": evaluate(eng, gen, L2, False)}
        rows.append(r); print(json.dumps(r, ensure_ascii=False), flush=True)
        with open(a.out, "a", encoding="utf-8") as f:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    for dm in ("greedy_search", "modified_beam_search"):
        run(dm, Engine(spec, a.threads, dm, a.max_active_paths), "-", 0.0)
    for lst in a.lists.split(","):
        words = {"L1": L1, "L2": L2}[lst]
        f = tmp / f"{lst}.txt"; hw_file(words, spec.kind, f)
        for sc in map(float, a.scores.split(",")):
            run("modified_beam_search+hw", Engine(spec, a.threads, "modified_beam_search", a.max_active_paths, str(f), sc), lst, sc)


if __name__ == "__main__":
    main()
