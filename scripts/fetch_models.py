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
import json
import shutil
import sys
import tarfile
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
    size: int | None = None  # expected bytes (exact, checked when known)
    sha256: str | None = None  # OFFICIAL digest of the (extracted) file (HF lfs / GH digest / checksum.txt)
    member: str | None = None  # `url` is a .tar.bz2: extract this file name from it (dest = its name in the cache)
    archive_sha256: str | None = None  # official digest of the archive
    alias: tuple[str, ...] = ()  # existing cache paths (relative to the root) to hard-link if their sha256 matches


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
        "MIT (salute-developers/GigaAM)", ("",), kind="asr",
        note="no punctuation (superseded by gigaam-v3-rnnt-punct-int8 in all tiers)",
    ),
    Model(
        "asr/gigaam-v2-ctc-int8",
        _giga("", "sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19", "ctc",
              {"model.int8.onnx": "d0ce4aef25f58d495781ee8f05320d9e51b821f47804e07aa6549b53a72f67e8"},
              {"model.int8.onnx": 236457977}),
        "MIT (salute-developers/GigaAM)", ("",), kind="asr",
        note="previous lite model (OfflineNemoEncDecCtcModelConfig)",
    ),
    Model(
        "asr/whisper-large-v3-turbo-int8",
        (hf("csukuangfj/sherpa-onnx-whisper-turbo", "turbo-encoder.int8.onnx", dest="encoder.int8.onnx",
            size=674716297, sha="b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b"),
         hf("csukuangfj/sherpa-onnx-whisper-turbo", "turbo-decoder.int8.onnx", dest="decoder.int8.onnx",
            size=361080764, sha="20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547"),
         hf("csukuangfj/sherpa-onnx-whisper-turbo", "turbo-tokens.txt", dest="tokens.txt", size=816730)),
        "MIT (openai/whisper large-v3-turbo)", ("",), kind="asr",
        note="candidate (hard second pass, currently unused): 1.0 GB int8, OfflineWhisperModelConfig",
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
        "MIT (salute-developers/GigaAM)", ("lite", "medium", "hard"), kind="asr",
        note="ru ASR of every tier: emits punctuation + case natively (punctuation.source=asr_native)",
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
    Model(
        "asr/whisper-tiny-int8",
        (hf("csukuangfj/sherpa-onnx-whisper-tiny", "tiny-encoder.int8.onnx", dest="encoder.int8.onnx",
            size=12937772, sha="d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434"),
         hf("csukuangfj/sherpa-onnx-whisper-tiny", "tiny-decoder.int8.onnx", dest="decoder.int8.onnx",
            size=89855401, sha="d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"),
         hf("csukuangfj/sherpa-onnx-whisper-tiny", "tiny-tokens.txt", dest="tokens.txt", size=816730)),
        "MIT (openai/whisper tiny, multilingual)", ("lite", "medium", "hard"), kind="asr",
        note="language id (language.lid_model)",
    ),
    Model(
        "asr/parakeet-tdt-110m-en-int8",
        tuple(F(f"{GH}/asr-models/sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8.tar.bz2", n,
                size, None, member=n,
                archive_sha256="f628312e9fdf8686374cb01a69425c41732529d540860311f16f37cbc32cfe9b")
              for n, size in [("encoder.int8.onnx", 131113202), ("decoder.int8.onnx", 3955863),
                              ("joiner.int8.onnx", 1411403), ("tokens.txt", 9953)]),
        "CC-BY-4.0 (NVIDIA parakeet-tdt_ctc-110m)", ("lite", "medium", "hard"), kind="asr",
        note="English ASR (asr_en, engine nemo_tdt); only the official .tar.bz2 release carries the weights",
    ),
    Model(
        "asr/parakeet-tdt-0.6b-v3-int8",
        (hf("csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "encoder.int8.onnx", size=652184281,
            sha="acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
         hf("csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "decoder.int8.onnx", size=11845275,
            sha="179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
         hf("csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "joiner.int8.onnx", size=6355277,
            sha="3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
         hf("csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "tokens.txt", size=93939)),
        "CC-BY-4.0 (NVIDIA Parakeet-TDT-0.6B-v3, 25 European languages incl. ru+en)", ("",), kind="asr",
        note="candidate: one multilingual transducer for ru+en; TDT gives token durations",
    ),
    Model(
        "asr/streaming-zipformer-small-ru-int8",
        tuple(F(f"{HF}/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/main/{n}", n, size, sha,
                alias=(f"sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/{n}",))
              for n, size, sha in [
                  ("encoder.int8.onnx", 26214060, "e0db705e94ec35d803b1df4f40cda23d064e1142977c80ab288430b109777a9d"),
                  ("decoder.onnx", 2093080, "89b3088a9e20e1ef7f2e85ce1a3478afe6a9c4ac57369cabcc4beb8e95328ea0"),
                  ("joiner.int8.onnx", 259417, "b55784b071ab7512eab4c7c44e4f5478284ef33c83562cc6a249b972515a31e5"),
                  ("tokens.txt", 6388, "93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6")]),
        "Apache-2.0 (alphacep vosk-model-small-streaming-ru)", ("lite", "medium", "hard"), kind="asr",
        note="live transcription while recording (config `live`), streaming zipformer; preview only",
    ),
    # ---------------------------------------------------------------- diarization
    Model(
        "diar/pyannote-segmentation-3.0",
        (hf("csukuangfj/sherpa-onnx-pyannote-segmentation-3-0", "model.int8.onnx", size=1540506,
            sha="d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d"),
         hf("csukuangfj/sherpa-onnx-pyannote-segmentation-3-0", "LICENSE")),
        "MIT (pyannote/segmentation-3.0, ONNX export by k2-fsa)", ("lite", "medium", "hard"), kind="diar-seg",
    ),
    # speaker embeddings (3D-Speaker, Apache-2.0): one per tier, used by the relabel stage and the stock diarizer
    *(
        Model(path, (F(f"{GH}/speaker-recongition-models/{fname}", "model.onnx", size, sha,
                       alias=(f"diar-emb-{fname[:-5]}/model.onnx",)),),
              "Apache-2.0 (modelscope/3D-Speaker; the release notes defer to the model repository)", (tier,),
              kind="diar-emb", note=note)
        for path, fname, size, sha, tier, note in [
            ("diar/emb-campplus-zh-cn", "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx", 28281138,
             "f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11", "lite", "CAM++ (lite)"),
            ("diar/emb-eres2net-base-200k", "3dspeaker_speech_eres2net_base_200k_sv_zh-cn_16k-common.onnx", 39593765,
             "e2d2048292e055f7b61cdec3db010503f35369b245bf0b3bbad021c9a91e4053", "medium", "ERes2Net base 200k (medium)"),
            ("diar/emb-eres2netv2", "3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx", 71441526,
             "bf1a75b9930474cf3389ef415e6e5d38ca96fea4a3a00f7e301d080a58ee2239", "hard", "ERes2Net v2 (hard)"),
        ]
    ),
    Model(
        "diar/embedding",
        (F(f"{GH}/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
           "model.onnx", 28281164, "aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2"),),
        "Apache-2.0 (modelscope/3D-Speaker CAM++ zh_en advanced)", ("",), kind="diar-emb",
        note="previous default embedding (superseded by diar/emb-* per tier)",
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
        (F(f"{GH}/speech-enhancement-models/gtcrn_simple.onnx", "gtcrn_simple.onnx", 535638,
           "e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534"),),
        "MIT (Xiaobin-Rong/gtcrn)", ("hard",), kind="denoise",
    ),
    # ---------------------------------------------------------------- LLM (GGUF)
    # Official Qwen/Qwen3-0.6B-GGUF and -1.7B-GGUF ship only Q8_0; ggml-org (llama.cpp authors) has
    # Q4_0 for 0.6B and Q4_K_M for 1.7B. 4B Q4_K_M: Qwen's own repo (byte-identical size, official sha).
    Model(
        "llm",
        (F(f"{HF}/ggml-org/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_0.gguf", "qwen3-0.6b-q4_0.gguf",
           None, "da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4"),),
        "Apache-2.0 (Qwen3)", ("lite",), kind="llm", note="0.6B Q4_K_M is not published officially -> Q4_0",
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
        (F(f"{HF}/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf", "qwen3-4b-q4_k_m.gguf",
           2497280256, "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5",
           alias=("llm-qwen3-4b-q4_k_m/Qwen3-4B-Q4_K_M.gguf",)),),
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
            with urllib.request.urlopen(req, timeout=120) as r:
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
        if f.size and p.stat().st_size != f.size:
            return False
    return True


def read_sha_file(d: Path) -> dict[str, str]:
    out = {}
    f = d / ".sha256"
    if f.is_file():
        for line in f.read_text().splitlines():
            if "  " in line:
                h, n = line.split("  ", 1)
                out[n] = h
    return out


def write_sha_file(d: Path, updates: dict[str, str]) -> None:
    """Merge `updates` (name -> sha256) into d/.sha256 (sha256sum -c compatible, sorted)."""
    cur = read_sha_file(d)
    cur.update(updates)
    cur = {n: h for n, h in cur.items() if (d / n).is_file()}
    tmp = d / ".sha256.tmp"
    tmp.write_text("".join(f"{cur[n]}  {n}\n" for n in sorted(cur)))
    os.replace(tmp, d / ".sha256")


def extract_member(archive: Path, member: str, out: Path) -> None:
    """Extract one regular file `member` (matched by base name) from a .tar.bz2 into `out` (no code is executed)."""
    with tarfile.open(archive, "r:bz2") as tf:
        for ti in tf:
            if ti.isreg() and Path(ti.name).name == member and ".." not in Path(ti.name).parts:
                with tf.extractfile(ti) as src, out.open("wb") as dst:
                    shutil.copyfileobj(src, dst, 1 << 20)
                return
    raise RuntimeError(f"{member} not found in {archive.name}")


def fetch(m: Model, root: Path) -> None:
    key = model_key(m)
    dest = root / m.dir
    if is_complete(m, root) and all((read_sha_file(dest).get(f.dest) or "") for f in m.files):
        print(f"[skip] {key}: already in {dest}")
        return
    tmp = root / f".tmp-refpipe-{key.replace('/', '_')}"
    tmp.mkdir(parents=True, exist_ok=True)
    print(f"[get ] {key} ({m.license})")
    digests: dict[str, str] = {}
    for f in m.files:
        t = tmp / f.dest
        # 1. reuse an existing copy under another name, but only if its sha256 equals the official one
        for al in f.alias:
            ap_ = root / al
            if f.sha256 and ap_.is_file() and not t.exists() and sha256_file(ap_) == f.sha256:
                try:
                    os.link(ap_, t)
                except OSError:
                    shutil.copy2(ap_, t)
                print(f"    {f.dest}: reused {al} (sha256 matches the official one)")
                break
        for attempt in (1, 2):  # a resumed download can be corrupted by a lost partial read: redo from scratch once
            if not t.exists():
                if f.member:
                    arc = tmp / Path(f.url).name
                    for a2 in (1, 2):
                        if not arc.exists():
                            download(f.url, arc)
                        if not f.archive_sha256 or sha256_file(arc) == f.archive_sha256:
                            break
                        arc.unlink()
                        if a2 == 2:
                            raise RuntimeError(f"archive sha256 mismatch: {f.url}")
                    extract_member(arc, f.member, t)
                else:
                    download(f.url, t)
            digest = sha256_file(t)
            if not f.sha256 or digest == f.sha256:
                break
            t.unlink()
            if attempt == 2:
                raise RuntimeError(f"sha256 mismatch for {f.url}: got {digest}, expected {f.sha256}")
            print("    sha256 mismatch, downloading again from scratch", flush=True)
        if f.size and t.stat().st_size != f.size:
            raise RuntimeError(f"size mismatch for {f.url}: {t.stat().st_size} vs {f.size}")
        print(f"    {f.dest}: {t.stat().st_size / 1e6:.1f} MB sha256={digest[:16]}.. "
              f"{'(official)' if f.sha256 else '(archive official)' if f.archive_sha256 else '(recorded)'}")
        digests[f.dest] = digest
    dest.mkdir(parents=True, exist_ok=True)
    for f in m.files:
        os.replace(tmp / f.dest, dest / f.dest)  # atomic per file (same filesystem)
    shutil.rmtree(tmp, ignore_errors=True)
    write_sha_file(dest, digests)


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


# ---------------------------------------------------------------------------------------------- verify
def config_model_paths(cfg: dict) -> set[str]:
    """Every model path (relative to the models root) a tier config refers to."""
    out: set[str] = set()

    def add(v):
        if isinstance(v, str) and v:
            out.add(v)

    add((cfg.get("vad") or {}).get("model"))
    for k in ("asr", "asr_en"):
        c = cfg.get(k) or {}
        add(c.get("model_dir"))
        add((c.get("second_pass") or {}).get("model_dir"))
        add(c.get("hotwords_file"))
    add((cfg.get("language") or {}).get("lid_model"))
    d = cfg.get("diarization") or {}
    add(d.get("segmentation"))
    add(d.get("embedding"))
    add((cfg.get("audio", {}).get("denoise") or {}).get("model"))
    add((cfg.get("llm") or {}).get("model"))
    add((cfg.get("punctuation") or {}).get("model"))
    add((cfg.get("live") or {}).get("model_dir"))
    return out


def table_paths(tier: str | None = None) -> dict[str, tuple[Model, F, str]]:
    """path in the models root -> (model, file, key)"""
    out = {}
    for m in MODELS:
        if tier and tier not in m.tiers:
            continue
        for f in m.files:
            out[f"{m.dir}/{f.dest}"] = (m, f, model_key(m))
    return out


def check_configs(repo: Path) -> list[str]:
    """Problems: config paths that the table does not provide."""
    problems = []
    allp = table_paths()
    dirs = {m.dir for m in MODELS}
    for cf in sorted((repo / "config" / "tiers").glob("*.json")):
        cfg = json.loads(cf.read_text(encoding="utf-8"))
        tier = cfg.get("tier", cf.stem)
        have = table_paths(tier)
        for path in sorted(config_model_paths(cfg)):
            ok = path in have or any(pp.startswith(path.rstrip("/") + "/") for pp in have)
            if not ok:
                where = "exists for other tiers only" if (path in allp or path in dirs) else "NOT in the table"
                problems.append(f"{cf.name}: {path}: {where}")
    return problems


def verify(root: Path, tier: str | None, repo: Path, deep: bool = True) -> int:
    bad = 0
    print(f"verify {root} tier={tier or 'all'}")
    for problem in check_configs(repo):
        print(f"  [CONFIG] {problem}")
        bad += 1
    rows = table_paths(None if tier in (None, "all") else tier)
    rows = {p: v for p, v in rows.items() if not (tier in (None, "all") and "" in v[0].tiers and len(v[0].tiers) == 1)}
    for path, (m, f, key) in sorted(rows.items()):
        p = root / path
        if not p.is_file():
            print(f"  [MISSING] {path}")
            bad += 1
            continue
        size = p.stat().st_size
        rec = read_sha_file(p.parent).get(p.name)
        status = "ok"
        if f.size and size != f.size:
            status = f"SIZE {size} != {f.size}"
        elif deep:
            h = sha256_file(p)
            if f.sha256 and h != f.sha256:
                status = f"SHA256 {h[:12]} != official {f.sha256[:12]}"
            elif rec and h != rec:
                status = f"SHA256 {h[:12]} != recorded {rec[:12]}"
            elif not rec:
                status = "no .sha256 record"
            elif not f.sha256:
                status += " (matches recorded sha; no official digest for the file)"
            else:
                status += " (matches official sha256)"
        if status.startswith(("SIZE", "SHA", "no ")):
            bad += 1
        print(f"  [{'OK' if status.startswith('ok') else 'BAD'}] {path:60} {size / 1e6:9.1f} MB  {status}")
    print("verify:", "FAILED" if bad else "all good", f"({bad} problems)")
    return 1 if bad else 0


def write_models_json(root: Path, out: Path) -> None:
    items = []
    for path, (m, f, key) in sorted(table_paths().items()):
        p = root / path
        if not p.is_file():
            continue
        rec = read_sha_file(p.parent).get(p.name) or sha256_file(p)
        items.append({
            "path": path,
            "url": f.url if not f.member else f"{f.url}#{f.member}",
            "sha256": rec,
            "sha256_source": "official" if f.sha256 else ("archive-official+extracted" if f.archive_sha256 else "recorded"),
            "size": p.stat().st_size,
            "license": m.license,
            "tiers": [t for t in m.tiers if t] or ["candidate"],
            "kind": m.kind,
            "note": m.note,
        })
    doc = {"_note": "Generated by scripts/fetch_models.py --write-models-json. path is relative to the models root "
                    "(Android assets/models, PC .cache/models) and equals the paths in config/tiers/*.json. "
                    "sha256_source: official = digest published by the source (HF lfs / GitHub digest / checksum.txt); "
                    "recorded = computed on first download (no digest is published for the file).",
           "models": items}
    out.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out} ({len(items)} files)")


def main() -> int:
    here = Path(__file__).resolve().parent
    repo = here.parent
    default_root = repo / ".cache" / "models"
    if ".worktrees" in here.parts:  # shared cache lives in the main checkout
        default_root = Path(*here.parts[: here.parts.index(".worktrees")]) / ".cache" / "models"
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tier", choices=["lite", "medium", "hard", "all"], help="tier(s) to download / verify")
    ap.add_argument("--root", type=Path, default=default_root, help=f"models root (default {default_root})")
    ap.add_argument("--candidates", action="store_true", help="also download candidate models for benches")
    ap.add_argument("--only", nargs="*", help="download only these dirs (e.g. asr/gigaam-v3-ctc-int8)")
    ap.add_argument("--no-llm", action="store_true", help="skip GGUF LLMs")
    ap.add_argument("--list", action="store_true", help="print the model table and exit")
    ap.add_argument("--verify", action="store_true", help="check files (size + sha256) of --tier (default all) and that "
                    "config/tiers/*.json only reference paths provided by the table")
    ap.add_argument("--write-models-json", type=Path, metavar="PATH", help="write config/models.json from the files on disk")
    a = ap.parse_args()
    if a.list:
        print_table()
        return 0
    if a.verify:
        return verify(a.root, a.tier, repo)
    if a.write_models_json:
        write_models_json(a.root, a.write_models_json)
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
