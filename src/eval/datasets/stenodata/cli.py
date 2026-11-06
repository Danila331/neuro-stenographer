"""CLI: python -m stenodata {fetch,build,validate,stats} ..."""
from __future__ import annotations

import argparse
import sys

from .common import raw_root


def main(argv=None):
    ap = argparse.ArgumentParser(prog="stenodata")
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fetch", help="скачать исходные корпусы в .cache/data/.raw")
    f.add_argument("sources", nargs="*")
    b = sub.add_parser("build", help="собрать датасеты")
    b.add_argument("which", nargs="*", default=["all"], choices=["all", "asr", "synth", "tts", "en-asr", "en-synth", "mixed", "real"],
                   help="all = ru-asr-dev, ru-dialog-synth, ru-dialog-tts-cats, en-asr-dev, en-dialog-synth, ru-en-mixed")
    b.add_argument("--seed", type=int, default=0)
    b.add_argument("--n-asr", type=int, default=600, help="число базовых фраз ru-asr-dev (+~18%% искажённых вариантов)")
    b.add_argument("--n-synth", type=int, default=30, help="число диалогов ru-dialog-synth")
    b.add_argument("--n-tts", type=int, default=40, help="число диалогов ru-dialog-tts-cats (сценарии берутся по кругу)")
    b.add_argument("--n-en-asr", type=int, default=220)
    b.add_argument("--n-en-synth", type=int, default=14)
    b.add_argument("--n-mixed", type=int, default=5)
    b.add_argument("--no-say", action="store_true", help="не использовать macOS say (Milena)")
    sub.add_parser("validate", help="проверить форматы").add_argument("names", nargs="*")
    st = sub.add_parser("stats", help="статистика (markdown)")
    st.add_argument("names", nargs="*")
    st.add_argument("--update-readme", action="store_true")
    a = ap.parse_args(argv)
    if a.cmd == "fetch":
        from . import fetch
        return fetch.main(a.sources)
    if a.cmd == "validate":
        from . import validate
        return validate.main(a.names)
    if a.cmd == "stats":
        from . import stats
        return stats.main(a.names + (["--update-readme"] if a.update_readme else []))
    which = {"asr", "synth", "tts", "en-asr", "en-synth", "mixed", "real"} if "all" in a.which else set(a.which)
    if "real" in which:
        from . import real
        print("->", real.init_real())
    if "asr" in which:
        from . import build_asr
        print("->", build_asr.build(n=a.n_asr, seed=a.seed))
    if "synth" in which:
        from . import build_synth
        print("->", build_synth.build(n=a.n_synth, seed=a.seed))
    if "tts" in which:
        from . import build_tts
        print("->", build_tts.build(n=a.n_tts, seed=a.seed, use_say=not a.no_say))
    if {"en-asr", "en-synth", "mixed"} & which and not (raw_root() / "librispeech").exists():
        print("! LibriSpeech не скачан (python -m stenodata fetch librispeech) — английские наборы пропущены", file=sys.stderr)
        which -= {"en-asr", "en-synth", "mixed"}
    if "en-asr" in which:
        from . import build_asr
        print("->", build_asr.build_en(n=a.n_en_asr, seed=a.seed))
    if "en-synth" in which:
        from . import build_synth
        print("->", build_synth.build_en(n=a.n_en_synth, seed=a.seed))
    if "mixed" in which:
        from . import build_synth
        print("->", build_synth.build_mixed(n=a.n_mixed, seed=a.seed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
