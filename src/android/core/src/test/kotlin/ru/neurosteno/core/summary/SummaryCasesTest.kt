package ru.neurosteno.core.summary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Паритет Kotlin <-> bench_rag на кейсах `summary_cases.json`, сгенерированных ИЗ ФУНКЦИЙ ЭТАЛОНА (`scripts/gen_summary_cases.py`):
 * стеммер и термы, долины и план фрагментов, GBNF, разбор + проверка ссылок, склейка тем, JSON для UI, extractive-выжимка.
 */
class SummaryCasesTest {
    private val cases: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/summary_cases.json")!!.bufferedReader(Charsets.UTF_8).readText()).jsonObject
    }
    private val enc = Json { encodeDefaults = true }

    private fun JsonElement.ints() = jsonArray.map { it.jsonPrimitive.int }
    private fun JsonElement.strs() = jsonArray.map { it.jsonPrimitive.content }

    private fun doc(name: String): List<SUtt> = cases["docs"]!!.jsonObject[name]!!.jsonArray.mapIndexed { i, r ->
        val a = r.jsonArray
        val sid = a[0].jsonPrimitive.content
        SUtt(i, sid, sid.substringAfter('_').toInt(), a[1].jsonPrimitive.long, a[2].jsonPrimitive.long, a[3].jsonPrimitive.content)
    }

    @TestFactory
    fun stemmer() = listOf(DynamicTest.dynamicTest("stem/snow5/trunc5 (${cases["stem"]!!.jsonArray.size} слов)") {
        val bad = ArrayList<String>()
        for (c in cases["stem"]!!.jsonArray) {
            val a = c.jsonArray
            val w = a[0].jsonPrimitive.content
            val got = listOf(SummaryText.stem(w), SummaryText.normTerm(w, "snow5"), SummaryText.normTerm(w, "trunc5"))
            val exp = listOf(1, 2, 3).map { a[it].jsonPrimitive.content }
            if (got != exp) bad += "$w: $got != $exp"
        }
        assertEquals(emptyList<String>(), bad.take(20))
    })

    @TestFactory
    fun termsCases() = cases["terms"]!!.jsonArray.mapIndexed { i, c ->
        DynamicTest.dynamicTest("terms #$i") {
            val a = c.jsonArray
            val t = a[0].jsonPrimitive.content
            assertEquals(a[1].strs(), SummaryText.terms(t))
            assertEquals(a[2].strs(), SummaryText.terms(t, dropQueryWords = true))
            assertEquals(a[3].strs(), SummaryText.terms(t, mode = "snow5"))
        }
    }

    @TestFactory
    fun valleys() = cases["segment"]!!.jsonArray.mapIndexed { i, c ->
        DynamicTest.dynamicTest("boundaries #$i") {
            val o = c.jsonObject
            val vecs = o["vecs"]!!.jsonArray.map { v -> v.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray() }
            val got = TopicSegmenter.boundaries(
                vecs, o["chars"]!!.ints(), o["k"]!!.jsonPrimitive.int, o["alpha"]!!.jsonPrimitive.double,
                o["min_utts"]!!.jsonPrimitive.int, o["max_chars"]!!.jsonPrimitive.int, o["min_chars"]!!.jsonPrimitive.int,
            )
            assertEquals(o["expected"]!!.ints(), got)
        }
    }

    @TestFactory
    fun plans() = cases["plan"]!!.jsonArray.mapIndexed { i, c ->
        DynamicTest.dynamicTest("plan #$i ${c.jsonObject["doc"]!!.jsonPrimitive.content} ${c.jsonObject["kind"]!!.jsonPrimitive.content}") {
            val o = c.jsonObject
            val utts = doc(o["doc"]!!.jsonPrimitive.content)
            val target = o["target"]!!.jsonPrimitive.int
            val got = if (o["kind"]!!.jsonPrimitive.content == "lexical") {
                SummaryPlanner.plan(utts, TopicSegmenter.lexicalVectors(utts.map { it.text }), target, SegmenterConfig(alpha = o["alpha"]!!.jsonPrimitive.double), o["k"]!!.jsonPrimitive.int)
            } else SummaryPlanner.plan(utts, null, target)
            assertEquals(o["expected"]!!.jsonArray.map { it.ints() }, got.map { listOf(it.first, it.second) })
        }
    }

    @TestFactory
    fun grammars() = cases["grammar"]!!.jsonArray.mapIndexed { i, c ->
        DynamicTest.dynamicTest("grammar #$i") {
            val o = c.jsonObject
            val utts = doc(o["doc"]!!.jsonPrimitive.content)
            val lo = o["lo"]!!.jsonPrimitive.int
            val hi = o["hi"]!!.jsonPrimitive.int
            assertEquals(o["expected"]!!.jsonPrimitive.content, SummaryPlanner.grammar(utts, lo, hi))
            assertEquals(o["expected_any"]!!.jsonPrimitive.content, SummaryPlanner.grammar(utts, lo, hi, ownRefs = false))
        }
    }

    @TestFactory
    fun lines() = cases["lines"]!!.jsonArray.map { c ->
        DynamicTest.dynamicTest("line ${c.jsonObject["i"]}") {
            val o = c.jsonObject
            val utts = doc(o["doc"]!!.jsonPrimitive.content)
            assertEquals(o["expected"]!!.jsonPrimitive.content, SummaryPlanner.line(utts[o["i"]!!.jsonPrimitive.int], o["local"]!!.jsonPrimitive.int))
        }
    }

    private fun topicsJson(utts: List<SUtt>, topics: List<Topic>, overview: String = ""): JsonObject {
        val d = SummaryEngine.toDoc(utts, topics, "llm", overview = overview)
        val full = enc.encodeToJsonElement(SummaryDoc.serializer(), d).jsonObject
        return JsonObject(mapOf("overview" to full["overview"]!!, "topics" to full["topics"]!!))
    }

    @TestFactory
    fun validation() = cases["validate"]!!.jsonArray.map { c ->
        val o = c.jsonObject
        val name = o["doc"]!!.jsonPrimitive.content
        DynamicTest.dynamicTest("validate+merge $name") {
            val utts = doc(name)
            val v = SummaryValidator(utts)
            val frags = o["frags"]!!.jsonArray.map { it.ints() }
            val kept = ArrayList<Topic>()
            for ((fi, f) in frags.withIndex()) {
                val st = SummaryStats()
                val parsed = SummaryValidator.parse(SummaryPlanner.extractJson(o["raw"]!!.jsonArray[fi].jsonPrimitive.content), st)
                val t = if (parsed != null) v.validate(parsed, f[0], f[1], st, 0.3, true, 0.5) else null
                val s = o["stats"]!!.jsonArray[fi].ints()
                assertEquals(s, listOf(st.refsTotal, st.refsBadRange, st.refsBadSpeaker, st.relinked, st.itemsTotal, st.itemsDropped, st.parseFailed), "stats frag $fi")
                val exp = o["topics"]!!.jsonArray[fi]
                if (t == null) assertTrue(exp is kotlinx.serialization.json.JsonNull, "frag $fi: ожидалась пустая тема") else {
                    assertEquals(exp.jsonArray.first(), (topicsJson(utts, listOf(t))["topics"] as JsonArray).first(), "frag $fi")
                    kept += t
                }
            }
            val merged = SummaryMerger.merge(kept, 0.45)
            val exp = o["merged"]!!.jsonObject
            assertEquals(JsonObject(mapOf("overview" to JsonPrimitive("ov"), "topics" to exp["topics"]!!)), topicsJson(utts, merged, "ov"))
        }
    }

    @TestFactory
    fun extractive() = cases["extractive"]!!.jsonArray.mapIndexed { i, c ->
        val o = c.jsonObject
        val name = o["doc"]!!.jsonPrimitive.content
        val kind = o["kind"]!!.jsonPrimitive.content
        DynamicTest.dynamicTest("extractive #$i $name $kind") {
            val utts = doc(name)
            val topics = if (kind == "lexical") {
                val k = o["k"]!!.jsonPrimitive.int
                ExtractiveSummary.digest(utts, TopicSegmenter.lexicalVectors(utts.map { it.text }), 1500, SegmenterConfig(), k)
            } else ExtractiveSummary.digest(utts, null, 1500, SegmenterConfig())
            val exp = o["expected"]!!.jsonObject
            assertEquals(JsonObject(mapOf("overview" to JsonPrimitive(""), "topics" to exp["topics"]!!)), topicsJson(utts, topics))
        }
    }

    @TestFactory
    fun cueMarkers() = listOf(DynamicTest.dynamicTest("маркеры решений и задач (${cases["marks"]!!.jsonArray.size})") {
        val bad = ArrayList<String>()
        for (c in cases["marks"]!!.jsonArray) {
            val a = c.jsonArray
            val t = a[0].jsonPrimitive.content
            if (ExtractiveSummary.isDecision(t) != a[1].jsonPrimitive.boolean || ExtractiveSummary.isTask(t) != a[2].jsonPrimitive.boolean) bad += t
        }
        assertEquals(emptyList<String>(), bad.take(10))
    })
}
