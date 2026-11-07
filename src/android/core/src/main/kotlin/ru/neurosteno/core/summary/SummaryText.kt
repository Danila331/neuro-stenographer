package ru.neurosteno.core.summary

/**
 * Лёгкая нормализация русского текста -- порт `bench_rag/text.py` (1:1, включая особенности эталона).
 * Стеммер -- Snowball Russian; режимы основы: `snow` (стемм), `snow5` (стемм, обрезанный до 5 символов), `trunc5`, `none`.
 */
object SummaryText {
    private const val VOWELS = "аеиоуыэюя"

    val STOP: Set<String> = (
        "и в во не что он на я с со как а то все она так его но да ты к у же вы за бы по только ее мне было вот от меня " +
            "еще нет о из ему теперь когда даже ну вдруг ли если уже или ни быть был него до вас нибудь опять уж вам сказал " +
            "ведь там потом себя ничего ей может они тут где есть надо ней для мы тебя их чем была сам чтоб без будто чего " +
            "раз тоже себе под будет ж тогда кто этот того потому этого какой совсем ним здесь этом один почти мой тем чтобы " +
            "нее сейчас были куда зачем всех никогда можно при два другой хоть после над больше тот через эти нас про всего " +
            "них какая много разве три эту моя впрочем хорошо свою этой перед иногда лучше чуть том нельзя такой им более " +
            "всегда конечно всю между это"
        ).split(' ').filter { it.isNotEmpty() }.toSet()

    val QUERY_STOP: Set<String> = (
        "говорил говорила говорили говорит сказал сказала сказали сказано рассказал рассказала обсуждали обсуждалось " +
            "обсуждал обсуждала тема теме тему темы записи записях записа запись истории расскажи найди покажи какие какая " +
            "какой какое каким какую кто кому кого чем чему сколько говорящий говорящего говорящему говорящим говорящая " +
            "спикер собеседник собеседника упоминал упоминали упоминается решили решение"
        ).split(' ').filter { it.isNotEmpty() }.toSet()

    private val PERF1 = listOf("вшись", "вши", "в") // нужна предшествующая а/я
    private val PERF2 = listOf("ившись", "ывшись", "ивши", "ывши", "ив", "ыв")
    private val REFL = listOf("ся", "сь")
    private val ADJ = listOf(
        "ее", "ие", "ые", "ое", "ими", "ыми", "ей", "ий", "ый", "ой", "ем", "им", "ым", "ом", "его", "ого",
        "ему", "ому", "их", "ых", "ую", "юю", "ая", "яя", "ою", "ею",
    )
    private val PART1 = listOf("ем", "нн", "вш", "ющ", "щ")
    private val PART2 = listOf("ивш", "ывш", "ующ")
    private val VERB1 = listOf("ла", "на", "ете", "йте", "ли", "й", "л", "ем", "н", "ло", "но", "ет", "ют", "ны", "ть", "ешь", "нно")
    private val VERB2 = listOf(
        "ила", "ыла", "ена", "ейте", "уйте", "ите", "или", "ыли", "ей", "уй", "ил", "ыл", "им", "ым", "ен",
        "ило", "ыло", "ено", "ят", "ует", "уют", "ит", "ыт", "ены", "ить", "ыть", "ишь", "ую", "ю",
    )
    private val NOUN = listOf(
        "а", "ев", "ов", "ие", "ье", "е", "иями", "ями", "ами", "еи", "ии", "и", "ией", "ей", "ой", "ий", "й",
        "иям", "ям", "ием", "ем", "ам", "ом", "о", "у", "ах", "иях", "ях", "ы", "ь", "ию", "ью", "ю", "ия", "ья", "я",
    )
    private val SUPER = listOf("ейше", "ейш")
    private val DERIV = listOf("ость", "ост")

    private fun rv(w: String): Int {
        for (i in w.indices) if (w[i] in VOWELS) return i + 1
        return w.length
    }

    /**
     * R2 как в эталоне: на i = 0 Python читает w[-1] (последний символ) -- воспроизводим, чтобы результат совпадал с эталоном.
     */
    private fun r2(w: String): Int {
        fun r1(s: Int): Int {
            for (i in s until w.length - 1) {
                val prev = if (i == 0) w[w.length - 1] else w[i - 1]
                if (w[i] !in VOWELS && prev in VOWELS) return i + 1
            }
            return w.length
        }
        return r1(r1(0))
    }

    private fun strip(w: String, rv: Int, g1: List<String>, g2: List<String>): String? {
        var best: String? = null
        for (e in g2) if (w.endsWith(e) && w.length - e.length >= rv && (best == null || e.length > best.length)) best = e
        for (e in g1) {
            if (w.endsWith(e) && w.length - e.length - 1 >= rv - 1 && w.length > e.length &&
                (w[w.length - e.length - 1] == 'а' || w[w.length - e.length - 1] == 'я') && (best == null || e.length > best.length)
            ) best = e
        }
        return best?.let { w.substring(0, w.length - it.length) }
    }

    private fun plain(w: String, rv: Int, endings: List<String>): String? {
        var best: String? = null
        for (e in endings) if (w.endsWith(e) && w.length - e.length >= rv && (best == null || e.length > best.length)) best = e
        return best?.let { w.substring(0, w.length - it.length) }
    }

    fun stem(word: String): String {
        var w = word.replace('ё', 'е')
        if (w.isEmpty() || w[0] < 'а' || w[0] > 'я') return w
        val rv = rv(w)
        val r2 = r2(w)
        val x = strip(w, rv, PERF1, PERF2)
        if (x != null) {
            w = x
        } else {
            plain(w, rv, REFL)?.let { w = it }
            var done = false
            val a = plain(w, rv, ADJ)
            if (a != null) {
                w = a
                strip(w, rv, PART1, PART2)?.let { w = it }
                done = true
            }
            if (!done) {
                val v = strip(w, rv, VERB1, VERB2)
                if (v != null) { w = v; done = true }
            }
            if (!done) plain(w, rv, NOUN)?.let { w = it }
        }
        if (w.endsWith("и") && w.length - 1 >= rv) w = w.substring(0, w.length - 1)
        for (e in DERIV) {
            if (w.endsWith(e) && w.length - e.length >= r2) { w = w.substring(0, w.length - e.length); break }
        }
        if (w.endsWith("нн")) {
            w = w.substring(0, w.length - 1)
        } else {
            var matched = false
            for (e in SUPER) {
                if (w.endsWith(e) && w.length - e.length >= rv) {
                    w = w.substring(0, w.length - e.length)
                    if (w.endsWith("нн")) w = w.substring(0, w.length - 1)
                    matched = true
                    break
                }
            }
            if (!matched && w.endsWith("ь") && w.length - 1 >= rv) w = w.substring(0, w.length - 1)
        }
        return w
    }

    private val cache = HashMap<String, String>()

    @Synchronized
    fun stemCached(w: String): String = cache.getOrPut(w) { stem(w) }

    private fun isTokenChar(c: Char) = (c in 'a'..'z') || (c in 'а'..'я') || (c in '0'..'9')

    /** Слова `[a-zа-я0-9]+` после lower и ё->е. */
    fun tokens(text: String): List<String> {
        val s = text.lowercase().replace('ё', 'е')
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            if (isTokenChar(s[i])) {
                var j = i
                while (j < s.length && isTokenChar(s[j])) j++
                out += s.substring(i, j)
                i = j
            } else i++
        }
        return out
    }

    fun normTerm(t: String, mode: String = "snow"): String {
        if (mode == "none") return t
        if (mode == "trunc5") return t.take(5)
        val s = stemCached(t)
        return if (mode == "snow5") s.take(5) else s
    }

    /** Основы слов без стоп-слов и однобуквенных (кроме цифр). Порядок и повторы сохраняются. */
    fun terms(text: String, dropQueryWords: Boolean = false, mode: String = "snow"): List<String> {
        val out = ArrayList<String>()
        for (t in tokens(text)) {
            if (t in STOP || (dropQueryWords && t in QUERY_STOP)) continue
            if (t.length == 1 && !t[0].isDigit()) continue
            out += normTerm(t, mode)
        }
        return out
    }
}
