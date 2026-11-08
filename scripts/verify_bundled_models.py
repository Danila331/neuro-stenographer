import hashlib
import json
import sys
import zipfile
from pathlib import Path


def required_paths(cfg):
    out = []
    def add(path):
        if path and path not in out:
            out.append(path)
    add((cfg.get("vad") or {}).get("model"))
    denoise = cfg.get("audio", {}).get("denoise", {}) or {}
    if denoise.get("enabled"):
        add(denoise.get("model"))
    for key in ("asr", "asr_en"):
        block = cfg.get(key) or {}
        add(block.get("model_dir"))
        add((block.get("second_pass") or {}).get("model_dir"))
    language = cfg.get("language") or {}
    if str(language.get("mode", "ru")).lower() == "auto":
        add(language.get("lid_model"))
    diar = cfg.get("diarization") or {}
    add(diar.get("segmentation"))
    add(diar.get("embedding"))
    add((cfg.get("punctuation") or {}).get("model"))
    live = cfg.get("live") or {}
    if live.get("enabled"):
        add(live.get("model_dir"))
    search = cfg.get("search") or {}
    if search.get("enabled", True):
        embedder = search.get("embedder") or {}
        add(embedder.get("model"))
        add(embedder.get("tokenizer"))
    add((cfg.get("llm") or {}).get("model"))
    return out


def main():
    apk = Path(sys.argv[1]).resolve()
    tier = sys.argv[2]
    cfg = json.loads((apk.parents[2] / "config" / "tiers" / f"{tier}.json").read_text(encoding="utf-8"))
    required = required_paths(cfg)
    with zipfile.ZipFile(apk) as zf:
        manifest_name = f"assets/models/{tier}/manifest.json"
        manifest = json.loads(zf.read(manifest_name))
        recorded = {entry["path"]: entry for entry in manifest["files"]}
        names = set(zf.namelist())
        verified_files = 0
        for path in required:
            candidates = [path] if path in recorded else [key for key in recorded if key.startswith(path.rstrip("/") + "/")]
            if not candidates:
                raise AssertionError(f"missing model or model directory: {path}")
            for relative in candidates:
                entry_name = f"assets/models/{tier}/{relative}"
                if entry_name not in names:
                    raise AssertionError(f"missing APK entry: {entry_name}")
                expected = recorded[relative]
                info = zf.getinfo(entry_name)
                if info.file_size != expected["size"]:
                    raise AssertionError(f"size mismatch: {relative}")
                digest = hashlib.sha256(zf.read(entry_name)).hexdigest()
                if digest != expected["sha256"]:
                    raise AssertionError(f"sha256 mismatch: {relative}")
                verified_files += 1
    print(json.dumps({"apk": str(apk), "tier": tier, "required_paths_verified": len(required),
                      "files_verified": verified_files, "manifest_models": len(recorded),
                      "apk_bytes": apk.stat().st_size}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
