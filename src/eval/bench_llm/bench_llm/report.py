"""Aggregate results/*.json into results/REPORT.md (tables)."""
from __future__ import annotations

import json
from pathlib import Path

from .llama import HERE, CATALOG, model_path

R = HERE / "results"
WPM = 130.0
AUDIO_S_PER_1000W = 1000 / WPM * 60  # 461.5 s of speech per 1000 words

# Anchor for phone estimates: llama.cpp issue #26957 (Galaxy S23 Ultra, Snapdragon 8 Gen 2, CPU only, -t 5,
# Qwen3.5-2B Q4_K_M 1.30 GiB): decode ~14 tok/s, prefill ~65-73 tok/s (thermally controlled).
PHONE_TG_ANCHOR = 14.0   # tok/s at 1.30 GiB
PHONE_PP_ANCHOR = 70.0
ANCHOR_GIB = 1.30


def load(p: Path):
    return json.loads(p.read_text(encoding="utf-8"))


def speeds(threads: int = 4) -> dict:
    out = {}
    for m in CATALOG:
        p = R / "speed" / f"{m}_t{threads}.json"
        if p.exists():
            out[m] = load(p)
    return out


def phone_speed(model: str) -> tuple[float, float]:
    gib = model_path(model).stat().st_size / 2**30
    return PHONE_PP_ANCHOR * ANCHOR_GIB / gib, PHONE_TG_ANCHOR * ANCHOR_GIB / gib


def combine(res: dict, key: str) -> float:
    """Recompute micro F1 over fleurs+dialog from per-set prec/rec/support."""
    tp = fp = fn = 0.0
    for s in ("fleurs", "dialog"):
        sup, rec, pr = res[s]["support"][key], res[s]["rec"][key], res[s]["prec"][key]
        t = rec * sup
        tp += t
        fn += sup - t
        fp += (t / pr - t) if pr else 0
    p = tp / (tp + fp) if tp + fp else 0
    r = tp / (tp + fn) if tp + fn else 0
    return 2 * p * r / (p + r) if p + r else 0


def model_time(res: dict, model: str, sp: dict):
    """Seconds per 1000 words: (M3 4t modelled, phone modelled)."""
    words = res["fleurs"]["n_words"] + res["dialog"]["n_words"]
    gen = (res["fleurs"]["gen_tokens_per_word"] * res["fleurs"]["n_words"] + res["dialog"]["gen_tokens_per_word"] * res["dialog"]["n_words"]) / words
    pr = (res["fleurs"]["prompt_tokens_per_word"] * res["fleurs"]["n_words"] + res["dialog"]["prompt_tokens_per_word"] * res["dialog"]["n_words"]) / words
    m3 = None
    if model in sp:
        m3 = 1000 * (pr / sp[model]["pp256_tok_s"] + gen / sp[model]["tg64_tok_s"])
    pp, tg = phone_speed(model)
    return gen, pr, m3, 1000 * (pr / pp + gen / tg)


def punct_table() -> str:
    sp = speeds()
    rows = []
    for p in sorted((R / "punct").glob("*__*.json")):
        model, variant = p.stem.split("__")[0], p.stem.split("__")[1]
        res = load(p)
        if model.startswith("rupunct"):
            rows.append((model, "baseline", res))
            continue
        rows.append((model, variant, res))
    hdr = ("| model | var | guard raw | F1 marks (FLEURS / dialogs) | F1 marks all | F1 `,` | F1 `.` | F1 `?` | F1 caps all | F1 caps inner | "
           "gen tok/word | prompt tok/word | s/1000 words M3 4t (model) | s/1000 words phone (est.) | RTF add M3 | RTF add phone |\n"
           "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
    body = ""
    for model, variant, res in rows:
        if variant == "baseline":
            ms = (res["fleurs"]["ms_per_word"] * res["fleurs"]["n_words"] + res["dialog"]["ms_per_word"] * res["dialog"]["n_words"]) / (res["fleurs"]["n_words"] + res["dialog"]["n_words"])
            body += (f"| {model} | - | 1.00 | {res['fleurs']['f1']['marks']:.3f} / {res['dialog']['f1']['marks']:.3f} | {combine(res,'marks'):.3f} | "
                     f"{combine(res,','):.3f} | {combine(res,'.'):.3f} | {combine(res,'?'):.3f} | {combine(res,'caps'):.3f} | {combine(res,'caps_inner'):.3f} | - | - | "
                     f"{ms:.2f} (measured, loaded) | ~{ms*3:.1f} | {ms/AUDIO_S_PER_1000W:.4f} | ~{ms*3/AUDIO_S_PER_1000W:.4f} |\n")
            continue
        gen, pr, m3, ph = model_time(res, model, sp)
        guard = (res["fleurs"]["guard_pass"] * res["fleurs"]["n_utts"] + res["dialog"]["guard_pass"] * res["dialog"]["n_utts"]) / (res["fleurs"]["n_utts"] + res["dialog"]["n_utts"])
        body += (f"| {model} | {variant} | {guard:.2f} | {res['fleurs']['f1']['marks']:.3f} / {res['dialog']['f1']['marks']:.3f} | {combine(res,'marks'):.3f} | "
                 f"{combine(res,','):.3f} | {combine(res,'.'):.3f} | {combine(res,'?'):.3f} | {combine(res,'caps'):.3f} | {combine(res,'caps_inner'):.3f} | {gen:.2f} | {pr:.2f} | "
                 f"{'%.1f' % m3 if m3 else 'n/a'} | {ph:.0f} | {'%.3f' % (m3/AUDIO_S_PER_1000W) if m3 else 'n/a'} | {ph/AUDIO_S_PER_1000W:.2f} |\n")
    return hdr + body


def diarfix_table() -> str:
    out = "| model | mode | word errors before -> after | fixed | broken | guard fails / calls | s per dialog-minute (loaded M3) |\n|---|---|---|---|---|---|---|\n"
    for p in sorted((R / "diarfix").glob("*.json")):
        r = load(p)
        b = sum(d["err_before"] for d in r["dialogs"])
        a = sum(d["err_after"] for d in r["dialogs"])
        fx = sum(d["fixed"] for d in r["dialogs"])
        br = sum(d["broken"] for d in r["dialogs"])
        gf = sum(d["guard_fail"] for d in r["dialogs"])
        ca = sum(d["calls"] for d in r["dialogs"])
        t = sum(d["prompt_ms"] + d["predicted_ms"] for d in r["dialogs"]) / 1000
        out += f"| {r['model']} | {r['mode']} | {b} -> {a} | {fx} | {br} | {gf}/{ca} | {t:.0f} s total |\n"
    return out


def protocol_table() -> str:
    out = ("| model | JSON valid | facts recall | quotes verbatim | quote time right | timecodes valid | QA correct | prompt tok | gen tok | "
           "wall prefill+gen (loaded M3) |\n|---|---|---|---|---|---|---|---|---|---|\n")
    for p in sorted((R / "protocol").glob("*.json")):
        r = load(p)
        pr = r["protocol"]
        n = len(pr)
        valid = sum(x["valid_json"] for x in pr.values())
        ch = [x["check"] for x in pr.values() if x["check"]]
        fr = sum(c["facts_recall"] for c in ch) / max(1, len(ch))
        qv = sum(c["quotes_verbatim"] for c in ch), sum(c["quotes_n"] for c in ch)
        qt = sum(c["quotes_verbatim_with_right_time"] for c in ch)
        tv = sum(c["timecodes_valid"] for c in ch), sum(c["timecodes_n"] for c in ch)
        qa = [x["correct"] for v in r["qa"].values() for x in v]
        ptok = sum(x["prompt_n"] for x in pr.values()) / n
        gtok = sum(x["predicted_n"] for x in pr.values()) / n
        t = sum(x["prompt_ms"] + x["predicted_ms"] for x in pr.values()) / n / 1000
        out += (f"| {p.stem} | {valid}/{n} | {fr:.2f} | {qv[0]}/{qv[1]} | {qt}/{qv[1]} | {tv[0]}/{tv[1]} | {sum(qa)}/{len(qa)} | "
                f"{ptok:.0f} | {gtok:.0f} | {t:.0f} s |\n")
    return out


def speed_table() -> str:
    sp = speeds()
    out = ("| model | size MB | M3 CPU 4t pp256 tok/s | M3 CPU 4t tg64 tok/s | load avg at run | phone pp est. | phone tg est. |\n|---|---|---|---|---|---|---|\n")
    for m, s in sp.items():
        pp, tg = phone_speed(m)
        out += (f"| {m} | {s['size_bytes']/1e6:.0f} | {s['pp256_tok_s']:.0f} ± {s['pp256_std']:.0f} | {s['tg64_tok_s']:.1f} ± {s['tg64_std']:.1f} | "
                f"{s['load_avg'][0]:.0f} | {pp:.0f} | {tg:.1f} |\n")
    return out


def main():
    md = "# Auto-generated results\n\n## Speed (llama-bench)\n\n" + speed_table() + "\n## Punctuation\n\n" + punct_table() + \
         "\n## Speaker fix\n\n" + diarfix_table() + "\n## Protocol / QA\n\n" + protocol_table()
    (R / "REPORT.md").write_text(md, encoding="utf-8")
    print(md)


if __name__ == "__main__":
    main()
