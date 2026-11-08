import json
import time
from pathlib import Path

from refpipe.audio import decode_audio, normalize_gain
from refpipe.config import load_config
from refpipe.lid import LanguageId, adaptive_lid_plan
from refpipe.vad import SileroVad, speech_chunks_with_lid

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / ".cache/data/ru-dialog-tts-cats/audio"
OUT = ROOT / "artifacts/benchmarks/adaptive-lid-boundaries.json"


def main():
    cfg = load_config(ROOT / "config/tiers/medium.json")
    models = ROOT / ".cache/models"
    vad = SileroVad(cfg["vad"], models, 1)
    lang = cfg["language"]
    lid = LanguageId(models / lang["lid_model"], 1, float(lang["lid_seconds"]))
    started = time.perf_counter()
    files = []
    for path in sorted(DATA.glob("*.wav")):
        x = decode_audio(path, 16000)
        ng = cfg["audio"]["normalize_gain"]
        x_asr, _ = normalize_gain(x, ng) if ng.get("enabled") and ng.get("scope") == "asr" else (x, 0.0)
        _, original, fine = speech_chunks_with_lid(x, cfg, vad)
        final, raw, languages = adaptive_lid_plan(x_asr, original, fine, lid, int(lang.get("switch_min_ms", 6000)))
        files.append({
            "file": path.name,
            "original_chunks": len(original),
            "lid_chunks": len(fine),
            "final_chunks": len(final),
            "preserved": final == original,
            "all_russian": all(language == "ru" for language in languages),
            "mismatch": [] if final == original else {
                "original": [(c.start_ms, c.end_ms) for c in original],
                "final": [(c.start_ms, c.end_ms) for c in final],
                "raw": raw,
                "languages": languages,
            },
        })
    report = {
        "dataset": "ru-dialog-tts-cats",
        "files": len(files),
        "preserved_files": sum(f["preserved"] for f in files),
        "all_russian_files": sum(f["all_russian"] for f in files),
        "original_chunks": sum(f["original_chunks"] for f in files),
        "lid_chunks": sum(f["lid_chunks"] for f in files),
        "final_chunks": sum(f["final_chunks"] for f in files),
        "elapsed_s": round(time.perf_counter() - started, 3),
        "mismatches": [f for f in files if not f["preserved"]],
        "per_file": files,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k not in ("per_file", "mismatches")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
