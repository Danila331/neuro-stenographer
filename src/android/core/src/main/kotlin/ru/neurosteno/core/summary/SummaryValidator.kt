package ru.neurosteno.core.summary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Разбор ответа модели и проверка ссылок -- порт `bench_rag/summary.py` (parse_json, validate, _support).
 * Пункт остаётся, только если его ссылки существуют, принадлежат указанному говорящему (для пунктов) и покрывают слова пункта;
 * слабая ссылка перепривязывается к реплике фрагмента, которая это действительно говорит, иначе пункт выбрасывается.
 */
class SummaryValidator(private val utts: List<SUtt>) {
    private val termCache = HashMap<Int, Set<String>>()

    private fun uttTerms(i: Int): Set<String> = termCache.getOrPut(i) { SummaryText.terms(utts[i].text).toSet() }

    /** Доля основ слов [text], найденных в реплике [evidence] (индекс). */
    fun support(text: String, evidence: Int): Double {
        val a = SummaryText.terms(text).toSet()
        if (a.isEmpty()) return 1.0
        val b = uttTerms(evidence)
        return a.count { it in b }.toDouble() / a.size
    }

    private fun supportOf(a: Set<String>, evidence: Int): Double {
        if (a.isEmpty()) return 1.0
        val b = uttTerms(evidence)
        return a.count { it in b }.toDouble() / a.size
    }

    private fun refs(raw: JsonElement?, lo: Int, hi: Int, st: SummaryStats): List<Int> {
        val out = ArrayList<Int>()
        if (raw !is JsonArray) return out
        for (r in raw) {
            st.refsTotal++
            val v = asInt(r)
            if (v != null && v >= 1 && v <= hi - lo) out += lo + v - 1 else st.refsBadRange++
        }
        return out
    }

    private fun str(e: JsonElement?, default: String = ""): String = when (e) {
        null -> default
        is JsonPrimitive -> e.content
        else -> e.toString()
    }

    fun validate(
        d: JsonElement?, lo: Int, hi: Int, st: SummaryStats, minSupport: Double = 0.3, relink: Boolean = true, itemSupport: Double = 0.5,
    ): Topic? {
        if (d !is JsonObject) return null
        val title = str(d["title"]).trim()
        if (title.isEmpty()) return null
        val tp = Topic(title, lo, hi - 1)

        fun settle(txt: String, refs: List<Int>, speaker: Int?, thr: Double): List<Int> {
            val pool = (lo until hi).filter { speaker == null || utts[it].spk == speaker }
            val a = SummaryText.terms(txt).toSet()
            val poolSet = pool.toHashSet()
            var good = refs.filter { it in poolSet && supportOf(a, it) >= thr }
            if (good.isEmpty() && relink && pool.isNotEmpty()) {
                var best = pool[0]
                var bestS = supportOf(a, best)
                for (i in pool) { val s = supportOf(a, i); if (s > bestS) { best = i; bestS = s } } // первый максимум, как max() в Python
                if (bestS >= thr) { st.relinked++; good = listOf(best) }
            }
            return good.toSortedSet().toList()
        }

        (d["points"] as? JsonArray)?.forEach { pe ->
            val p = pe as? JsonObject ?: return@forEach
            st.itemsTotal++
            val sp = asInt(p["speaker"])
            val txt = str(p["text"]).trim()
            val rs = refs(p["refs"], lo, hi, st)
            if (sp != null) st.refsBadSpeaker += rs.count { utts[it].spk != sp }
            val good = if (sp != null && txt.isNotEmpty()) settle(txt, rs, sp, minSupport) else emptyList()
            if (good.isNotEmpty()) tp.points += Point(sp!!, txt, good) else st.itemsDropped++
        }
        for ((key, dst) in listOf("decisions" to tp.decisions, "tasks" to tp.tasks)) {
            (d[key] as? JsonArray)?.forEach { pe ->
                val p = pe as? JsonObject ?: return@forEach
                st.itemsTotal++
                val txt = str(p["text"]).trim()
                val rs = refs(p["refs"], lo, hi, st)
                val good = if (txt.isNotEmpty()) settle(txt, rs, null, itemSupport) else emptyList()
                if (good.isNotEmpty()) dst += Item(txt, good, str(p["who"]).trim()) else st.itemsDropped++
            }
        }
        return if (tp.points.isNotEmpty() || tp.decisions.isNotEmpty() || tp.tasks.isNotEmpty()) tp else null
    }

    companion object {
        private val json = Json

        /** `null` и счётчик `parseFailed`, если ответ не разобрался (обрезан лимитом токенов и т. п.). */
        fun parse(text: String, st: SummaryStats): JsonElement? = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            st.parseFailed++
            null
        }

        private val intRe = Regex("-?[0-9]+")

        fun asInt(e: JsonElement?): Int? {
            if (e !is JsonPrimitive || e.isString) return null
            return if (intRe.matches(e.content)) e.content.toIntOrNull() else null
        }
    }
}
