"""Токены+метки -> слова с временем; выравнивание ref/hyp для оценки временных меток."""
from __future__ import annotations
from .textnorm import norm


def words_from_tokens(tokens: list[str], ts: list[float], frame: float = 0.04, offset: float = 0.0):
    """-> [(text, start_s, end_s)]. Граница слова: токен с '▁' в начале либо токен ' ' (char-level GigaAM)."""
    words, cur, cs, ce = [], "", None, None
    for t, s in zip(tokens, ts):
        if t == " " or t == "▁":
            if cur:
                words.append((cur, cs + offset, ce + frame + offset)); cur, cs = "", None
            continue
        new = t.startswith("▁") or (t.startswith(" ") and len(t) > 1)
        piece = t.replace("▁", "").strip(" ")
        if new and cur:
            words.append((cur, cs + offset, ce + frame + offset)); cur, cs = "", None
        if cs is None:
            cs = s
        cur += piece; ce = s
    if cur:
        words.append((cur, cs + offset, ce + frame + offset))
    return words


def align_pairs(ref: list[str], hyp: list[str]) -> list[tuple[int | None, int | None]]:
    """Левенштейн с обратным проходом -> пары индексов (i_ref, j_hyp); None = пропуск."""
    n, m = len(ref), len(hyp)
    D = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1): D[i][0] = i
    for j in range(m + 1): D[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            D[i][j] = min(D[i - 1][j - 1] + (ref[i - 1] != hyp[j - 1]), D[i - 1][j] + 1, D[i][j - 1] + 1)
    i, j, out = n, m, []
    while i > 0 or j > 0:
        if i > 0 and j > 0 and D[i][j] == D[i - 1][j - 1] + (ref[i - 1] != hyp[j - 1]):
            out.append((i - 1, j - 1)); i -= 1; j -= 1
        elif i > 0 and D[i][j] == D[i - 1][j] + 1:
            out.append((i - 1, None)); i -= 1
        else:
            out.append((None, j - 1)); j -= 1
    return out[::-1]


def utterance_bounds_error(ref_utts: list[dict], hyp_words: list[tuple[str, float, float]]):
    """ref_utts: [{text,start_ms,end_ms}] (границы речи без лишней тишины). Для каждой реплики берём слова гипотезы,
    сопоставленные её словам (match/sub), и сравниваем start/end. -> [(err_start_s, err_end_s)] (signed: hyp - ref)."""
    rw, owner = [], []
    for k, u in enumerate(ref_utts):
        ws = norm(u["text"]).split()
        rw += ws; owner += [k] * len(ws)
    hw = [norm(w[0]) for w in hyp_words]
    pairs = align_pairs(rw, hw)
    first, last = {}, {}
    for i, j in pairs:
        if i is None or j is None:
            continue
        k = owner[i]
        first.setdefault(k, j); last[k] = j
    res = []
    for k, u in enumerate(ref_utts):
        if k in first:
            res.append((hyp_words[first[k]][1] - u["start_ms"] / 1000, hyp_words[last[k]][2] - u["end_ms"] / 1000))
    return res
