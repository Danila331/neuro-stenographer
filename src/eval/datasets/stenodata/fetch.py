"""Скачивание исходных корпусов в общий кэш `$ROOT/.cache/data/.raw/<name>/`.

Правила (docs/TEAM.md): только официальные источники без логинов/токенов, атомарная запись
(качаем в `.tmp-datasets-<name>/`, затем `os.rename`), существующее переиспользуем.

Источники:
  ruls      OpenSLR SLR96 (Russian LibriSpeech), потоково читаем только dev/ и test/ начала tar.gz
  fleurs    google/fleurs (HF, CC-BY-4.0): ru_ru dev+test
  golos     OpenSLR SLR114 (Golos), потоково читаем только test_opus/ из начала tar.gz
  musan     OpenSLR SLR17 (MUSAN), потоково берём только начало (music/fma), ~MAX_MUSAN_MB
  esc50     github.com/karolpiczak/ESC-50 (CC BY-NC 3.0; включает класс "cat" = мяуканье)
  piper     HF rhasspy/piper-voices: ru_RU голоса (onnx + json)
"""
from __future__ import annotations

import argparse
import io
import os
import shutil
import sys
import tarfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

from .common import raw_root

UA = {"User-Agent": "stenodata/0.1 (research; offline-eval)"}
HF = "https://huggingface.co"
RULS_URL = "https://www.openslr.org/resources/96/ruls_data.tar.gz"
GOLOS_URL = "https://www.openslr.org/resources/114/golos_opus.tar.gz"
MUSAN_URL = "https://www.openslr.org/resources/17/musan.tar.gz"
ESC50_URL = "https://github.com/karolpiczak/ESC-50/archive/refs/heads/master.zip"
PIPER_VOICES = ["ru/ru_RU/denis/medium/ru_RU-denis-medium", "ru/ru_RU/dmitri/medium/ru_RU-dmitri-medium",
                "ru/ru_RU/irina/medium/ru_RU-irina-medium", "ru/ru_RU/ruslan/medium/ru_RU-ruslan-medium"]


def _open(url: str, retries: int = 4):
    last = None
    for i in range(retries):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60)
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(2 * (i + 1))
    raise RuntimeError(f"cannot open {url}: {last}")


def _download(url: str, dst: Path, retries: int = 25):
    """Скачивание с докачкой (HTTP Range), проверкой размера и повторами."""
    dst.parent.mkdir(parents=True, exist_ok=True)
    last = None
    total = None
    for i in range(retries):
        have = dst.stat().st_size if dst.exists() else 0
        if total is not None and have >= total:
            return
        try:
            req = urllib.request.Request(url, headers={**UA, **({"Range": f"bytes={have}-"} if have else {})})
            with urllib.request.urlopen(req, timeout=90) as r:
                cl = r.headers.get("Content-Length")
                if r.status == 206:
                    cr = r.headers.get("Content-Range", "")
                    if "/" in cr and cr.split("/")[-1].isdigit():
                        total = int(cr.split("/")[-1])
                    elif cl:
                        total = have + int(cl)
                elif cl:
                    total = int(cl)
                    have = 0
                mode = "ab" if (have and r.status == 206) else "wb"
                with open(dst, mode) as f:
                    shutil.copyfileobj(r, f, 1 << 20)
            size = dst.stat().st_size
            if total is None or size >= total:
                return
            last = f"incomplete {size}/{total}"
        except urllib.error.HTTPError as e:
            if e.code == 416:  # уже скачано целиком
                return
            last = e
        except Exception as e:  # noqa: BLE001
            last = e
        time.sleep(min(30, 3 * (i + 1)))
    raise RuntimeError(f"download failed {url}: {last}")


def _atomic(name: str, build) -> Path:
    """build(tmp_dir) заполняет временную папку; затем rename в .raw/<name>."""
    final = raw_root() / name
    if (final / ".complete").exists():
        print(f"[fetch] {name}: already present, reuse")
        return final
    tmp = raw_root() / f".tmp-datasets-{name}"
    tmp.mkdir(parents=True, exist_ok=True)  # существующий tmp не стираем — докачка
    build(tmp)
    (tmp / ".complete").write_text("ok\n")
    if final.exists():
        shutil.rmtree(final)
    os.rename(tmp, final)
    print(f"[fetch] {name}: done -> {final}")
    return final


class _Counter(io.RawIOBase):
    """Обёртка над HTTP-ответом, считает скачанные байты и режет по лимиту."""

    def __init__(self, raw, limit_bytes: int | None = None):
        self.raw, self.n, self.limit = raw, 0, limit_bytes

    def readable(self):
        return True

    def readinto(self, b):
        if self.limit is not None and self.n >= self.limit:
            return 0
        data = self.raw.read(len(b))
        b[: len(data)] = data
        self.n += len(data)
        return len(data)


def stream_tar(url: str, out: Path, keep, stop, limit_bytes: int | None = None, label: str = ""):
    """Потоково читает tar.gz; извлекает файлы, для которых keep(name); останавливается на stop(name)."""
    resp = _open(url)
    cnt = _Counter(resp, limit_bytes)
    buf = io.BufferedReader(cnt, 1 << 20)
    n_files, last = 0, time.time()
    try:
        with tarfile.open(fileobj=buf, mode="r|gz") as tf:
            for m in tf:
                if stop(m.name):
                    break
                if not m.isfile() or not keep(m.name):
                    continue
                dst = out / m.name
                dst = dst.resolve()
                if out.resolve() not in dst.parents:  # защита от path traversal
                    continue
                dst.parent.mkdir(parents=True, exist_ok=True)
                with open(dst, "wb") as f:
                    shutil.copyfileobj(tf.extractfile(m), f, 1 << 20)
                n_files += 1
                if time.time() - last > 30:
                    print(f"[fetch:{label}] {n_files} files, {cnt.n/1e6:.0f} MB downloaded, at {m.name}", flush=True)
                    last = time.time()
    except (tarfile.ReadError, EOFError, OSError) as e:
        if limit_bytes is None:
            raise
        print(f"[fetch:{label}] stopped at byte limit ({e.__class__.__name__}) after {n_files} files")
    finally:
        resp.close()
    print(f"[fetch:{label}] {n_files} files, {cnt.n/1e6:.0f} MB downloaded")


def fetch_ruls():
    """RuLS test (7 дикторов, PD, OpenSLR SLR96).

    Потоковое чтение tar.gz с openslr.org упирается в dev/ (1 диктор, ~1 ГБ) и медленный канал, поэтому
    берём тот же test-сплит из HF-зеркала istupakov/russian_librispeech (parquet, ~300 МБ) --
    ОТКЛОНЕНИЕ от «официальных репозиториев авторов» (зеркало, не автор); данные идентичны SLR96.
    """
    def build(tmp: Path):
        import io as _io
        import json as _json

        import pyarrow.parquet as pq
        import soundfile as sf

        pqf = tmp / "test.parquet"
        base = f"{HF}/datasets/istupakov/russian_librispeech/resolve/main"
        _download(f"{base}/data/test-00000-of-00001.parquet", pqf)
        _download(f"{base}/CHAPTERS_READERS.TXT", tmp / "CHAPTERS_READERS.TXT")
        _download(f"{base}/BOOKS.TXT", tmp / "BOOKS.TXT")
        pf = pq.ParquetFile(pqf)
        with open(tmp / "manifest.jsonl", "w", encoding="utf-8") as mf:
            for batch in pf.iter_batches(batch_size=64):
                for r in batch.to_pylist():
                    rel = r["audio_filepath"]  # audio/<spk>/<book>/<name>.wav
                    dst = (tmp / "test" / rel).resolve()
                    if (tmp / "test").resolve() not in dst.parents:
                        continue
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    x, sr = sf.read(_io.BytesIO(r["audio"]["bytes"]), dtype="float32")
                    dst = dst.with_suffix(".wav")
                    sf.write(str(dst), x, sr, subtype="PCM_16")
                    mf.write(_json.dumps({"audio_filepath": str(dst.relative_to(tmp)), "duration": r["duration"],
                                          "text": r["text"], "text_raw": r["text_no_preprocessing"]}, ensure_ascii=False) + "\n")
        pqf.unlink()
    return _atomic("ruls", build)


def fetch_golos():
    def build(tmp: Path):
        stream_tar(GOLOS_URL, tmp, keep=lambda n: True,
                   stop=lambda n: not (n.startswith("test_opus") or n.startswith("./test_opus")), label="golos")
    return _atomic("golos", build)


def fetch_musan(max_mb: int = 450):
    def build(tmp: Path):
        stream_tar(MUSAN_URL, tmp, keep=lambda n: n.endswith(".wav") or n.endswith("README") or "LICENSE" in n,
                   stop=lambda n: False, limit_bytes=max_mb * 1_000_000, label="musan")
        # последний файл мог оборваться — удалим файлы нулевого/подозрительного размера
        for p in tmp.rglob("*.wav"):
            try:
                import soundfile as sf
                sf.info(str(p))
            except Exception:  # noqa: BLE001
                p.unlink()
    return _atomic("musan", build)


def fetch_esc50():
    def build(tmp: Path):
        z = tmp / "esc50.zip"
        _download(ESC50_URL, z)
        with zipfile.ZipFile(z) as zf:
            for n in zf.namelist():
                if n.endswith(".wav") or n.endswith("esc50.csv") or n.endswith("LICENSE"):
                    d = (tmp / "ESC-50" / Path(*Path(n).parts[1:])).resolve()
                    if tmp.resolve() not in d.parents:
                        continue
                    d.parent.mkdir(parents=True, exist_ok=True)
                    d.write_bytes(zf.read(n))
        z.unlink()
    return _atomic("esc50", build)


def _fetch_fleurs(lang_dir: str, name: str, splits=("dev", "test")):
    def build(tmp: Path):
        base = f"{HF}/datasets/google/fleurs/resolve/main/data/{lang_dir}"
        for sp in splits:
            for rel in (f"{sp}.tsv", f"audio/{sp}.tar.gz"):
                _download(f"{base}/{rel}", tmp / rel)
        for sp in splits:
            tgz = tmp / "audio" / f"{sp}.tar.gz"
            with tarfile.open(tgz) as tf:
                for m in tf:
                    dst = (tmp / "audio" / m.name).resolve()
                    if m.isfile() and (tmp / "audio").resolve() in dst.parents:
                        dst.parent.mkdir(parents=True, exist_ok=True)
                        dst.write_bytes(tf.extractfile(m).read())
            tgz.unlink()
        _download(f"{HF}/datasets/google/fleurs/resolve/main/README.md", tmp / "README.md")
    return _atomic(name, build)


def fetch_fleurs():
    return _fetch_fleurs("ru_ru", "fleurs", splits=("dev",))


def fetch_fleurs_en():
    return _fetch_fleurs("en_us", "fleurs_en", splits=("dev",))


LS_URLS = {"test-clean": "https://www.openslr.org/resources/12/test-clean.tar.gz",
           "test-other": "https://www.openslr.org/resources/12/test-other.tar.gz"}


def fetch_librispeech():
    """LibriSpeech test-clean + test-other (OpenSLR SLR12, CC BY 4.0): flac + транскрипты, ID дикторов в путях."""
    def build(tmp: Path):
        for sub, url in LS_URLS.items():
            stream_tar(url, tmp, keep=lambda n: n.endswith((".flac", ".txt")), stop=lambda n: False, label=sub)
    return _atomic("librispeech", build)


def fetch_piper():
    def build(tmp: Path):
        for v in PIPER_VOICES:
            for ext in (".onnx", ".onnx.json"):
                _download(f"{HF}/rhasspy/piper-voices/resolve/main/{v}{ext}", tmp / (Path(v).name + ext))
        _download(f"{HF}/rhasspy/piper-voices/resolve/main/ru/ru_RU/denis/medium/MODEL_CARD", tmp / "MODEL_CARD.denis")
    return _atomic("piper", build)


VF_URL = "http://www.repository.voxforge1.org/downloads/Russian/Trunk/Audio/Main/16kHz_16bit/"


def fetch_voxforge(max_subs: int = 420, max_anon: int = 120):
    """VoxForge ru (GPLv3): сессии записи отдельных дикторов (username из имени архива).

    Берём по <=2 сессии на каждый именованный username + до `max_anon` анонимных сессий.
    """
    import re
    from concurrent.futures import ThreadPoolExecutor

    def build(tmp: Path):
        with _open(VF_URL) as r:
            html = r.read().decode("utf-8", "replace")
        names = sorted(set(re.findall(r'href="([^"/]+\.tgz)"', html)))
        by_user: dict[str, list[str]] = {}
        for n in names:
            u = re.sub(r"-\d{8}.*", "", n)
            by_user.setdefault(u, []).append(n)
        chosen = []
        for u, lst in sorted(by_user.items()):
            chosen += lst[:max_anon] if u == "anonymous" else lst[:2]
        chosen = chosen[:max_subs]
        print(f"[fetch:voxforge] {len(chosen)} sessions of {len(names)} ({len(by_user)} usernames)")

        def one(n: str):
            try:
                with _open(VF_URL + n) as r:
                    data = r.read()
                with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tf:
                    for m in tf:
                        parts = Path(m.name).parts
                        if not m.isfile() or len(parts) < 3:
                            continue
                        if parts[1] in ("wav", "flac") or parts[-1] in ("prompts-original", "PROMPTS", "README", "LICENSE"):
                            dst = (tmp / n[:-4] / Path(*parts[1:])).resolve()
                            if (tmp / n[:-4]).resolve() not in dst.parents:
                                continue
                            dst.parent.mkdir(parents=True, exist_ok=True)
                            dst.write_bytes(tf.extractfile(m).read())
                return n, True
            except Exception as e:  # noqa: BLE001
                print(f"[fetch:voxforge] skip {n}: {e}")
                return n, False

        with ThreadPoolExecutor(8) as ex:
            ok = sum(1 for _, k in ex.map(one, chosen) if k)
        print(f"[fetch:voxforge] downloaded {ok}/{len(chosen)}")
    return _atomic("voxforge", build)


ALL = {"ruls": fetch_ruls, "voxforge": fetch_voxforge, "fleurs": fetch_fleurs, "fleurs_en": fetch_fleurs_en, "librispeech": fetch_librispeech, "golos": fetch_golos, "musan": fetch_musan,
       "esc50": fetch_esc50, "piper": fetch_piper}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("sources", nargs="*", default=list(ALL), help=f"any of {list(ALL)}")
    a = ap.parse_args(argv)
    for s in a.sources:
        ALL[s]()


if __name__ == "__main__":
    sys.exit(main())
