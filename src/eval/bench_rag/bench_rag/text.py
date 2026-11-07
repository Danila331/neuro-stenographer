"""Light Russian text normalisation shared by BM25 and the summary checks.

Pure functions, no dependencies -> 1:1 portable to Kotlin (`:core`).
Stemmer = Snowball Russian (Porter-style), the classic published algorithm.
"""
from __future__ import annotations

import re

_TOKEN = re.compile(r"[a-zа-я0-9]+")
VOWELS = "аеиоуыэюя"

STOP = frozenset("""
и в во не что он на я с со как а то все она так его но да ты к у же вы за бы по только ее мне было вот от меня
еще нет о из ему теперь когда даже ну вдруг ли если уже или ни быть был него до вас нибудь опять уж вам сказал
ведь там потом себя ничего ей может они тут где есть надо ней для мы тебя их чем была сам чтоб без будто чего
раз тоже себе под будет ж тогда кто этот того потому этого какой совсем ним здесь этом один почти мой тем чтобы
нее сейчас были куда зачем всех никогда можно при два другой хоть после над больше тот через эти нас про всего
них какая много разве три эту моя впрочем хорошо свою этой перед иногда лучше чуть том нельзя такой им более
всегда конечно всю между это
""".split())

# words of the question itself that carry no topic ("что говорил ... про ...")
QUERY_STOP = frozenset("""
говорил говорила говорили говорит сказал сказала сказали сказано рассказал рассказала обсуждали обсуждалось
обсуждал обсуждала тема теме тему темы записи записях записа запись истории расскажи найди покажи какие какая
какой какое каким какую кто кому кого чем чему сколько говорящий говорящего говорящему говорящим говорящая
спикер собеседник собеседника упоминал упоминали упоминается решили решение
""".split())


def _rv(w: str) -> int:
    for i, c in enumerate(w):
        if c in VOWELS:
            return i + 1
    return len(w)


def _r2(w: str) -> int:
    def r1(s: int) -> int:
        for i in range(s, len(w) - 1):
            if w[i] not in VOWELS and w[i - 1] in VOWELS:
                return i + 1
        return len(w)
    return r1(r1(0))


_PERF1 = ("вшись", "вши", "в")           # need preceding а/я
_PERF2 = ("ившись", "ывшись", "ивши", "ывши", "ив", "ыв")
_REFL = ("ся", "сь")
_ADJ = ("ее", "ие", "ые", "ое", "ими", "ыми", "ей", "ий", "ый", "ой", "ем", "им", "ым", "ом", "его", "ого",
        "ему", "ому", "их", "ых", "ую", "юю", "ая", "яя", "ою", "ею")
_PART1 = ("ем", "нн", "вш", "ющ", "щ")   # need preceding а/я
_PART2 = ("ивш", "ывш", "ующ")
_VERB1 = ("ла", "на", "ете", "йте", "ли", "й", "л", "ем", "н", "ло", "но", "ет", "ют", "ны", "ть", "ешь", "нно")
_VERB2 = ("ила", "ыла", "ена", "ейте", "уйте", "ите", "или", "ыли", "ей", "уй", "ил", "ыл", "им", "ым", "ен",
          "ило", "ыло", "ено", "ят", "ует", "уют", "ит", "ыт", "ены", "ить", "ыть", "ишь", "ую", "ю")
_NOUN = ("а", "ев", "ов", "ие", "ье", "е", "иями", "ями", "ами", "еи", "ии", "и", "ией", "ей", "ой", "ий", "й",
         "иям", "ям", "ием", "ем", "ам", "ом", "о", "у", "ах", "иях", "ях", "ы", "ь", "ию", "ью", "ю", "ия", "ья", "я")
_SUPER = ("ейше", "ейш")
_DERIV = ("ость", "ост")


def _strip(w: str, rv: int, g1: tuple[str, ...], g2: tuple[str, ...]) -> str | None:
    """Longest ending from g1 (needs а/я before it, inside RV) or g2; returns new word or None."""
    best = None
    for e in g2:
        if w.endswith(e) and len(w) - len(e) >= rv and (best is None or len(e) > len(best)):
            best = e
    for e in g1:
        if w.endswith(e) and len(w) - len(e) - 1 >= rv - 1 and len(w) > len(e) and w[-len(e) - 1] in "ая" \
                and (best is None or len(e) > len(best)):
            best = e
    return None if best is None else w[: len(w) - len(best)]


def _plain(w: str, rv: int, endings: tuple[str, ...]) -> str | None:
    best = None
    for e in endings:
        if w.endswith(e) and len(w) - len(e) >= rv and (best is None or len(e) > len(best)):
            best = e
    return None if best is None else w[: len(w) - len(best)]


def stem(word: str) -> str:
    w = word.replace("ё", "е")
    if not w or not ("а" <= w[0] <= "я"):
        return w
    rv = _rv(w)
    r2 = _r2(w)
    # step 1
    x = _strip(w, rv, _PERF1, _PERF2)
    if x is not None:
        w = x
    else:
        x = _plain(w, rv, _REFL)
        if x is not None:
            w = x
        y = None
        # adjectival = adjective [participle]
        a = _plain(w, rv, _ADJ)
        if a is not None:
            w = a
            p = _strip(w, rv, _PART1, _PART2)
            if p is not None:
                w = p
            y = True
        if y is None:
            v = _strip(w, rv, _VERB1, _VERB2)
            if v is not None:
                w = v
                y = True
        if y is None:
            n = _plain(w, rv, _NOUN)
            if n is not None:
                w = n
    # step 2
    if w.endswith("и") and len(w) - 1 >= rv:
        w = w[:-1]
    # step 3
    for e in _DERIV:
        if w.endswith(e) and len(w) - len(e) >= r2:
            w = w[: -len(e)]
            break
    # step 4
    if w.endswith("нн"):
        w = w[:-1]
    else:
        for e in _SUPER:
            if w.endswith(e) and len(w) - len(e) >= rv:
                w = w[: -len(e)]
                if w.endswith("нн"):
                    w = w[:-1]
                break
        else:
            if w.endswith("ь") and len(w) - 1 >= rv:
                w = w[:-1]
    return w


_cache: dict[str, str] = {}


def stem_cached(w: str) -> str:
    s = _cache.get(w)
    if s is None:
        s = _cache[w] = stem(w)
    return s


def tokens(text: str) -> list[str]:
    return _TOKEN.findall(text.lower().replace("ё", "е"))


def norm_term(t: str, mode: str = "snow") -> str:
    """mode: snow = Snowball stem; trunc5 = first 5 chars; snow5 = Snowball stem cut to 5 chars; none."""
    if mode == "none":
        return t
    if mode == "trunc5":
        return t[:5]
    s = stem_cached(t)
    return s[:5] if mode == "snow5" else s


def terms(text: str, drop_query_words: bool = False, mode: str = "snow") -> list[str]:
    """Normalised index/query terms: lower, ё->е, stop-words dropped, stemmed (see norm_term)."""
    out = []
    for t in tokens(text):
        if t in STOP or (drop_query_words and t in QUERY_STOP):
            continue
        if len(t) == 1 and not t.isdigit():
            continue
        out.append(norm_term(t, mode))
    return out
