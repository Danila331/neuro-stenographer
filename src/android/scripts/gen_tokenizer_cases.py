"""JSON-кейсы паритета токенайзеров эмбеддеров (Kotlin `ru.neurosteno.core.search.tokenizer`) ПРЯМО из Python `tokenizers`:
строки (реальные окна корпуса cats + крайние случаи) -> id токенов для USER2-small (byte-level BPE) и rubert-tiny-turbo (WordPiece),
без усечения и с усечением до 16/256. Выход: core/src/test/resources/tokenizer_cases.json (сами tokenizer.json в репозиторий не входят:
тест берёт их из $NEUROSTENO_ROOT/.cache/models/embed и пропускается, если файлов нет).

  src/eval/bench_rag/.venv/bin/python src/android/scripts/gen_tokenizer_cases.py
"""
import json
import os
import random
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "src/eval/bench_rag"))
from tokenizers import Tokenizer  # noqa: E402

from bench_rag.chunker import chunk_record  # noqa: E402
from bench_rag.data import MODELS, load_record  # noqa: E402

OUT = REPO / "src/android/core/src/test/resources/tokenizer_cases.json"
MODELS_DIR = {"user2-small": MODELS / "embed/user2-small/tokenizer.json", "rubert-tiny-turbo": MODELS / "embed/rubert-tiny-turbo/tokenizer.json"}

EDGE = [
    "", " ", "  ", "   ", "    ", "      ", "                        ", "a", "Привет", "привет, мир!", "ПРИВЕТ Мир", "Hello, world!", "it's don't I'll we've they're I'm he'd",
    "Не'вменяемый 'так", "123 456 7890", "1,5 млн. руб.", "v2.0-beta", "e-mail: ivan@example.com", "http://example.org/path?q=1&x=2", "|||EMAIL_ADDRESS|||", "[MASK] слово", "слово [MASK]",
    "[CLS] [SEP] [PAD] [UNK]", "[unused5] текст", "<|endoftext|> конец", "a  b", "a   b", "a    b", "a\tb", "a\nb", "a \nb", "a\n\nb", "a \n b", "тест с nbsp", "тест em space",
    "ё Ё ёж Ёлка", "й й й", "café naïve résumé", "é ä", "Åström", "日本語のテキスト", "中文 文本 混合 mixed", "한국어 텍스트", "العربية نص", "עברית טקסט", "ไทย ข้อความ",
    "😀", "Привет 😀 мир 🎉🎉", "👨‍👩‍👧‍👦 семья", "©®™ символы $€£¥", "a+b=c; d<e>f", "«ёлочки» и „лапки“ — тире – дефис -", "скобки (так) [так] {так}", "...", "!!!???", "a_b_c __init__",
    "x" * 150, "ы" * 120, "слово" * 40, "Он сказал: \"Да!\" Она ответила: 'Нет.'", "Говорящий 1: Давайте обсудим бюджет.\nГоворящий 2: В июле потратили восемь тысяч.",
    "​zero​width‍ joiner", "ctrl\u0001char\u0007bell", "tab\there", "смесь Rus и English words 42 раза", "тысяча девятьсот девяносто девятый год", "ёёёёё", "№ 5", "%100", "50%", "1/2",
    "ΑΒΓ αβγ ελληνικά", "ǅ ǆ ǈ", "ﬁ ﬂ ligatures", "Ⅷ ⅷ ① ②", "٠١٢٣ ०१२", "a" + "́" * 10, "…", "﻿BOM start", "line1\r\nline2\rline3",
]


def corpus_strings(rnd: random.Random, n: int):
    out = []
    for rid in ["c01", "c02", "c03", "c05", "c09", "c11", "c13", "c18", "c21", "c30"]:
        for kind in ("asr", "ref"):
            try:
                r = load_record(rid, kind)
            except FileNotFoundError:
                continue
            for c in chunk_record(r, "win3s1"):
                out.append(c.embed_text)
                out.append(c.text)
    rnd.shuffle(out)
    return out[:n]


def main():
    rnd = random.Random(7)
    strings = EDGE + corpus_strings(rnd, 220)
    long_text = " ".join(corpus_strings(random.Random(1), 400))[:6000]  # длиннее 256 токенов -> усечение
    strings += [long_text, long_text[:2500]]
    cases = {"strings": strings, "models": {}}
    for name, path in MODELS_DIR.items():
        pre = {"user2-small": ("search_query: ", "search_document: "), "rubert-tiny-turbo": ("", "")}[name]
        tok = Tokenizer.from_file(str(path))
        res = {}
        for lim in (0, 16, 256):
            t = Tokenizer.from_file(str(path))
            if lim:
                t.enable_truncation(max_length=lim)
            else:
                t.no_truncation()
            res[str(lim)] = [t.encode(s).ids for s in strings]
        cases["models"][name] = {"ids": res, "prefix_q": pre[0], "prefix_d": pre[1], "vocab": len(tok.get_vocab())}
    OUT.write_text(json.dumps(cases, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(OUT, OUT.stat().st_size // 1024, "KiB;", len(strings), "строк")


if __name__ == "__main__":
    main()
