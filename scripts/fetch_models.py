#!/usr/bin/env python3
"""Download models for a tier into the shared cache (official sources only).

    python scripts/fetch_models.py --tier medium            # -> $ROOT/.cache/models
    python scripts/fetch_models.py --tier all --no-llm
    python scripts/fetch_models.py --candidates             # extra models for the benches
    python scripts/fetch_models.py --list                   # print the table, download nothing

Sources (TEAM.md rule 4): github.com/k2-fsa/sherpa-onnx releases, Hugging Face
`csukuangfj/*` (author of sherpa-onnx), `ggml-org/*` (GGUF). No login, no tokens.

Layout: <root>/<dir>/<file>; `dir` is exactly what `config/tiers/*.json` references
(e.g. `asr/gigaam-v3-rnnt-int8`). Every downloaded directory gets `.sha256`
(`sha256sum -c` compatible). Downloads go to `<root>/.tmp-refpipe-<name>/` and are moved into
place atomically (per file); an already complete model is reused.

Only the Python stdlib is needed.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
HF = "https://huggingface.co"


@dataclass(frozen=True)
class F:
    """One file to download. `dest` is the name inside the model directory."""

    url: str
    dest: str
    size: int | None = None  # expected bytes (checked when known)
    sha256: str | None = None  # expected digest (checked when known)


@dataclass(frozen=True)
class Model:
    dir: str  # relative to the models root; matches the path in config/tiers/*.json
    files: tuple[F, ...]
    license: str
    tiers: tuple[str, ...]  # tiers that need it ("" = candidate only)
    note: str = ""
    kind: str = ""  # vad|asr|diar-seg|diar-emb|denoise|llm
    extra: dict = field(default_factory=dict, hash=False, compare=False)


def hf(repo: str, name: str, dest: str | None = None, size=None, sha=None) -> F:
    return F(f"{HF}/{repo}/resolve/main/{name}", dest or Path(name).name, size, sha)


def _giga(ver_dir: str, repo_suffix: str, kind: str, sha: dict[str, str], sizes: dict[str, int]):
    """GigaAM transducer ('rnnt') or CTC from csukuangfj/sherpa-onnx-nemo-*."""
    repo = f"csukuangfj/{repo_suffix}"
    names = (
        ["encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "LICENSE"]
        if kind == "rnnt"
        else ["model.int8.onnx", "tokens.txt", "LICENSE"]
    )
    return tuple(hf(repo, n, sha=sha.get(n), size=sizes.get(n)) for n in names)


MODELS: tuple[Model, ...] = (
    # ---------------------------------------------------------------- VAD
    Model(
        "silero-vad",
        (F(f"{GH}/asr-models/silero_vad.onnx", "silero_vad.onnx", 643854,
           "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),),
        "MIT (snakers4/silero-vad v5)", ("lite", "medium", "hard"), kind="vad",
    ),
    # ---------------------------------------------------------------- ASR
    Model(
        "asr/gigaam-v3-rnnt-int8",
        _giga("", "sherpa-onnx-nemo-transducer-giga-am-v3-russian-2025-12-16", "rnnt",
              {"decoder.onnx": "5d1cfef155d8e07f213bd2f3229b5f6c5c29355c7fa5c1b9eb62cb1925725254",
               "encoder.int8.onnx": "0ec54e5f130a91c0f228d21795ede10b31c992a1f73e45dcd827e843a71a9b50",
               "joiner.onnx": "fd1d02f45c2ad3d6b67cc149811ad794ab4b020ed49a0a9e2790a8619d1cddd8"},
              {"encoder.int8.onnx": 224570814, "decoder.onnx": 3331651, "joiner.onnx": 1440448}),
        "MIT (salute-developers/GigaAM)", ("medium", "hard"), kind="asr",
        note="sherpa-onnx OfflineTransducer, model_type nemo_transducer; 'gigaam-v3 e2e rnnt'",
    ),
    Model(
        "asr/gigaam-v2-ctc-int8",
        _giga("", "sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19", "ctc",
              {"model.int8.onnx": "d0ce4aef25f58d495781ee8f05320d9e51b821f47804e07aa6549b53a72f67e8"},
              {"model.int8.onnx": 236457977}),
        "MIT (salute-developers/GigaAM)", ("lite",), kind="asr",
        note="OfflineNemoEncDecCtcModelConfig",
    ),
    Model(
        "asr/whisper-large-v3-turbo-int8",
        (hf("csukuangfj/sherpa-onnx-whisper-turbo", "turbo-encoder.int8.onnx", dest="encoder.int8.onnx",
            size=674716297, sha="b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b"),
         hf("csukuangfj/sherpa-onnx-whisper-turbo", "turbo-decoder.int8.onnx", dest="decoder.int8.onnx",
            size=361080764, sha="20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547"),
         hf("csukuangfj/sherpa-onnx-whisper-turbo", "turbo-tokens.txt", dest="tokens.txt", size=816730)),
        "MIT (openai/whisper large-v3-turbo)", ("hard",), kind="asr",
        note="1.0 GB int8 encoder+decoder; sherpa-onnx OfflineWhisperModelConfig, language=ru",
    ),
    # candidates for asr-bench (not required by any tier)
    Model(
        "asr/gigaam-v3-ctc-int8",
        _giga("", "sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16", "ctc",
              {"model.int8.onnx": "f86ebfa0429ced91be6054fc344827e9c6c2572f3c318416cd974b06f66437ec"},
              {"model.int8.onnx": 224721476}),
        "MIT (salute-developers/GigaAM)", ("",), kind="asr", note="candidate",
    ),
    Model(
        "asr/gigaam-v2-rnnt-int8",
        _giga("", "sherpa-onnx-nemo-transducer-giga-am-v2-russian-2025-04-19", "rnnt",
              {"decoder.onnx": "208e24cc150fb0ebca3fab169502796daa12e0255dcf7b4acf65015c436e9f76",
               "encoder.int8.onnx": "b51efc61e3c0037ad1cb804079975468de3d175324fe8323aef5be4f5c6a38a1",
               "joiner.onnx": "4b02eced18e033fc5173e6c47b6ab166b5efea8d35c3f33a6755ff0d622fb5b0"},
              {"encoder.int8.onnx": 236314144, "decoder.onnx": 3331651, "joiner.onnx": 1440448}),
        "MIT (salute-developers/GigaAM)", ("",), kind="asr", note="candidate",
    ),
    Model(
        "asr/gigaam-v3-rnnt-punct-int8",
        _giga("", "sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16", "rnnt",
              {"decoder.onnx": "38fc7475443ea2a26f63211ca350f73ac50fff824ab7a3876ee2bd610c53bbc4",
               "encoder.int8.onnx": "369f35a71bf288d3b8e0391fabd8dba5f2314088d440bca474056b7b4b6e66bf",
               "joiner.onnx": "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c"},
              {"encoder.int8.onnx": 224570820, "decoder.onnx": 4600132, "joiner.onnx": 2712896}),
        "MIT (salute-developers/GigaAM)", ("",), kind="asr",
        note="candidate: emits punctuation + case natively (punctuation.source=asr_native)",
    ),
    Model(
        "asr/gigaam-v3-ctc-punct-int8",
        _giga("", "sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16", "ctc",
              {"model.int8.onnx": "d5fea8df94263c285e54b21e5774b707c707192d3bdbeffd7b1eb07fb6743b35"},
              {"model.int8.onnx": 224893661}),
        "MIT (salute-developers/GigaAM)", ("",), kind="asr",
        note="candidate: CTC with punctuation + case",
    ),
    Model(
        "asr/zipformer-ru-int8",
        tuple(hf("csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20", n, size=s, sha=h) for n, s, h in [
            ("encoder.int8.onnx", 70876638, "eb6c12fbad810d5bc3e427802e604604c69b5943a91feebc43424dd09d9ec407"),
            ("decoder.onnx", 2093080, "dcbe1ffa0211e77ca6d3a80164df13fbda3ec00e47d12b9f449f89572df12136"),
            ("joiner.int8.onnx", 259417, "93f2e1d12b78d53e7802f1606488c14bb3d764b15fadf5ef6c022f6ba1fa40f7"),
            ("tokens.txt", 6388, None), ("bpe.model", 246184, "c7a756aeb3550417d6b2ed3efde9a7aa3eea54787d4eac011e9cce6090c9c64a")]),
        "Apache-2.0 (alphacep/vosk zipformer-ru)", ("",), kind="asr", note="candidate",
    ),
    # ---------------------------------------------------------------- diarization
    Model(
        "diar/pyannote-segmentation-3.0",
        (hf("csukuangfj/sherpa-onnx-pyannote-segmentation-3-0", "model.int8.onnx", size=1540506,
            sha="d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d"),
         hf("csukuangfj/sherpa-onnx-pyannote-segmentation-3-0", "LICENSE")),
        "MIT (pyannote/segmentation-3.0, ONNX export by k2-fsa)", ("lite", "medium", "hard"), kind="diar-seg",
    ),
    Model(
        "diar/embedding",
        (F(f"{GH}/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
           "model.onnx", 28281164),),
        "Apache-2.0 (modelscope/3D-Speaker CAM++ zh_en advanced)", ("lite", "medium", "hard"), kind="diar-emb",
        note="default embedding; no Russian-trained model exists in sherpa-onnx -- diar-bench decides",
    ),
    *(
        Model(f"diar/embedding-{tag}", (F(f"{GH}/speaker-recongition-models/{fname}", "model.onnx", size),),
             lic, ("",), kind="diar-emb", note="candidate for diar-bench")
        for tag, fname, size, lic in [
            ("eres2net-base", "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx", 39593761, "Apache-2.0 (3D-Speaker)"),
            ("eres2netv2", "3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx", 71441526, "Apache-2.0 (3D-Speaker)"),
            ("campplus-voxceleb-en", "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx", None, "Apache-2.0 (3D-Speaker)"),
            ("wespeaker-resnet34", "wespeaker_en_voxceleb_resnet34.onnx", 26534365, "Apache-2.0 (wenet-e2e/wespeaker)"),
            ("wespeaker-campplus", "wespeaker_en_voxceleb_CAM++.onnx", 29292684, "Apache-2.0 (wenet-e2e/wespeaker)"),
            ("titanet-small", "nemo_en_titanet_small.onnx", 40257283, "CC-BY-4.0 (NVIDIA NeMo TitaNet)"),
            ("titanet-large", "nemo_en_titanet_large.onnx", 101405493, "CC-BY-4.0 (NVIDIA NeMo TitaNet)"),
        ]
    ),
    # ---------------------------------------------------------------- denoise
    Model(
        "denoise",
        (F(f"{GH}/speech-enhancement-models/gtcrn_simple.onnx", "gtcrn_simple.onnx", 535638),),
        "MIT (Xiaobin-Rong/gtcrn)", ("hard",), kind="denoise",
    ),
    # ---------------------------------------------------------------- LLM (GGUF)
    # Official Qwen/Qwen3-0.6B-GGUF and -1.7B-GGUF ship only Q8_0; ggml-org (llama.cpp authors) has
    # Q4_0 for 0.6B and Q4_K_M for 1.7B/4B. 0.6B Q4_K_M does not exist officially.
    Model(
        "llm",
        (F(f"{HF}/ggml-org/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_0.gguf", "qwen3-0.6b-q4_0.gguf",
           429000000, "da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4"),),
        "Apache-2.0 (Qwen3)", ("lite",), kind="llm", note="0.6B Q4_K_M not published officially -> Q4_0",
        extra={"shared_dir": True},
    ),
    Model(
        "llm",
        (F(f"{HF}/ggml-org/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf", "qwen3-1.7b-q4_k_m.gguf",
           None, "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"),),
        "Apache-2.0 (Qwen3)", ("medium",), kind="llm", extra={"shared_dir": True},
    ),
    Model(
        "llm",
        (F(f"{HF}/ggml-org/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf", "qwen3-4b-q4_k_m.gguf",
           2497280000, "ab27b9bfa375a178d6cba48f3ad892b94b7739659dcc7aae8058ce0ffed6b328"),),
        "Apache-2.0 (Qwen3)", ("hard",), kind="llm", extra={"shared_dir": True},
    ),
)


def model_key(m: Model) -> str:
    return m.files[0].dest if m.extra.get("shared_dir") else m.dir


# ----------------------------------------------------------------------------------------------
def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for blk in iter(lambda: f.read(1 << 20), b""):
            h.update(blk)
    return h.hexdigest()


def download(url: str, dst: Path, retries: int = 4) -> None:
    """Stream `url` to `dst` (with .part + Range resume)."""
    part = dst.with_name(dst.name + ".part")
    for attempt in range(1, retries + 1):
        try:
            have = part.stat().st_size if part.exists() else 0
            req = urllib.request.Request(url, headers={"User-Agent": "refpipe-fetch/1.0"})
            if have:
                req.add_header("Range", f"bytes={have}-")
            with urllib.request.urlopen(req, timeout=60) as r:
                mode = "ab" if (have and r.status == 206) else "wb"
                total = int(r.headers.get("Content-Length") or 0) + (have if mode == "ab" else 0)
                done = have if mode == "ab" else 0
                t0, last = time.time(), 0.0
                with part.open(mode) as f:
                    while True:
                        blk = r.read(1 << 20)
                        if not blk:
                            break
                        f.write(blk)
                        done += len(blk)
                        if time.time() - last > 5 and total:
                            last = time.time()
                            print(f"    {dst.name}: {done / 1e6:.0f}/{total / 1e6:.0f} MB "
                                  f"({done / 1e6 / max(time.time() - t0, 1e-3):.1f} MB/s)", flush=True)
            os.replace(part, dst)
            return
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            if isinstance(e, urllib.error.HTTPError) and e.code == 416:
                os.replace(part, dst)
                return
            print(f"    retry {attempt}/{retries} after {e!r}", flush=True)
            time.sleep(2 * attempt)
    raise RuntimeError(f"download failed: {url}")


def is_complete(m: Model, root: Path) -> bool:
    d = root / m.dir
    for f in m.files:
        p = d / f.dest
        if not p.is_file() or p.stat().st_size == 0:
            return False
        if f.size and f.dest.endswith((".onnx", ".gguf")) and abs(p.stat().st_size - f.size) > max(1, f.size // 100):
            return False
    return True


def write_sha_file(d: Path) -> None:
    """(Re)write d/.sha256 for every regular file in d (stable, sorted)."""
    lines = []
    for p in sorted(d.iterdir()):
        if p.is_file() and not p.name.startswith("."):
            cached = _sha_cache.get(p)
            lines.append(f"{cached or sha256_file(p)}  {p.name}")
    tmp = d / ".sha256.tmp"
    tmp.write_text("\n".join(lines) + "\n")
    os.replace(tmp, d / ".sha256")


_sha_cache: dict[Path, str] = {}


def fetch(m: Model, root: Path) -> None:
    key = model_key(m)
    if is_complete(m, root):
        print(f"[skip] {key}: already in {root / m.dir}")
        return
    tmp = root / f".tmp-refpipe-{key.replace('/', '_')}"
    tmp.mkdir(parents=True, exist_ok=True)
    print(f"[get ] {key} ({m.license})")
    for f in m.files:
        t = tmp / f.dest
        if not t.exists():
            download(f.url, t)
        digest = sha256_file(t)
        if f.sha256 and digest != f.sha256:
            t.unlink()
            raise RuntimeError(f"sha256 mismatch for {f.url}: got {digest}, expected {f.sha256}")
        if f.size and f.dest.endswith((".onnx", ".gguf")) and abs(t.stat().st_size - f.size) > max(1, f.size // 100):
            raise RuntimeError(f"size mismatch for {f.url}: {t.stat().st_size} vs {f.size}")
        print(f"    {f.dest}: {t.stat().st_size / 1e6:.1f} MB sha256={digest[:16]}.. "
              f"{'(verified)' if f.sha256 else '(recorded)'}")
        _sha_cache[root / m.dir / f.dest] = digest
    dest = root / m.dir
    dest.mkdir(parents=True, exist_ok=True)
    for f in m.files:
        os.replace(tmp / f.dest, dest / f.dest)  # atomic per file (same filesystem)
    shutil.rmtree(tmp, ignore_errors=True)
    write_sha_file(dest)


def select(tier: str, candidates: bool, only: list[str] | None, no_llm: bool) -> list[Model]:
    out = []
    for m in MODELS:
        if no_llm and m.kind == "llm":
            continue
        k = model_key(m)
        if only and not any(o == k or o == m.dir for o in only):
            continue
        in_tier = tier == "all" and any(t for t in m.tiers) or tier in m.tiers
        is_cand = candidates and "" in m.tiers
        if (not only and (in_tier or is_cand)) or only:
            out.append(m)
    return out


def print_table() -> None:
    print(f"{'dir / file':52} {'tiers':18} {'MB':>7}  license")
    for m in MODELS:
        mb = sum(f.size or 0 for f in m.files) / 1e6
        print(f"{model_key(m):52} {','.join(t or 'candidate' for t in m.tiers):18} {mb:7.0f}  {m.license}")
        for f in m.files:
            print(f"    {f.url}")


def main() -> int:
    here = Path(__file__).resolve().parent
    default_root = here.parent / ".cache" / "models"
    if ".worktrees" in here.parts:  # shared cache lives in the main checkout
        default_root = Path(*here.parts[: here.parts.index(".worktrees")]) / ".cache" / "models"
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tier", choices=["lite", "medium", "hard", "all"], help="tier(s) to download")
    ap.add_argument("--root", type=Path, default=default_root, help=f"models root (default {default_root})")
    ap.add_argument("--candidates", action="store_true", help="also download candidate models for benches")
    ap.add_argument("--only", nargs="*", help="download only these dirs (e.g. asr/gigaam-v3-ctc-int8)")
    ap.add_argument("--no-llm", action="store_true", help="skip GGUF LLMs")
    ap.add_argument("--list", action="store_true", help="print the model table and exit")
    a = ap.parse_args()
    if a.list:
        print_table()
        return 0
    if not a.tier and not a.candidates and not a.only:
        ap.error("--tier, --candidates or --only required")
    root: Path = a.root
    root.mkdir(parents=True, exist_ok=True)
    todo = select(a.tier or "none", a.candidates, a.only, a.no_llm)
    seen, failed = set(), []
    for m in todo:
        k = model_key(m)
        if k in seen:
            continue
        seen.add(k)
        try:
            fetch(m, root)
        except Exception as e:  # keep going, report at the end
            print(f"[FAIL] {k}: {e}", file=sys.stderr)
            failed.append(k)
    print(f"done: {len(seen) - len(failed)} ok, {len(failed)} failed -> {root}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
