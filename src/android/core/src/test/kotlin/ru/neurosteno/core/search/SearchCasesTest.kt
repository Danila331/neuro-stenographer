package ru.neurosteno.core.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
import ru.neurosteno.core.summary.SUtt

/**
 * Паритет Kotlin <-> bench_rag на кейсах `search_cases.json` (`scripts/gen_search_cases.py`, прямо из функций эталона):
 * нарезка окон, BM25 (оценки бит в бит в float32), разбор говорящего, гибридный поиск с RRF (ранги и оценки), контекст и проверка ответа.
 */
class SearchCasesTest {
    private val cases: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/search_cases.json")!!.bufferedReader(Charsets.UTF_8).readText()).jsonObject
    }

    private fun JsonElement.strs() = jsonArray.map { it.jsonPrimitive.content }
    private fun JsonElement.ints() = jsonArray.map { it.jsonPrimitive.int }

    private fun record(o: JsonObject) = SearchRecord(
        o["id"]!!.jsonPrimitive.content, o["title"]!!.jsonPrimitive.content,
        o["utts"]!!.jsonArray.map {
            val u = it.jsonObject
            val sid = u["speaker"]!!.jsonPrimitive.content
            SUtt(u["idx"]!!.jsonPrimitive.int, sid, sid.substringAfter('_').toInt(), u["start_ms"]!!.jsonPrimitive.long, u["end_ms"]!!.jsonPrimitive.long, u["text"]!!.jsonPrimitive.content)
        },
        o["names"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
    )

    private fun chunk(o: JsonObject) = Chunk(
        o["cid"]!!.jsonPrimitive.long, o["record"]!!.jsonPrimitive.content, o["utts"]!!.ints(), o["speakers"]!!.strs(),
        o["start_ms"]!!.jsonPrimitive.long, o["end_ms"]!!.jsonPrimitive.long, o["text"]!!.jsonPrimitive.content, o["embed_text"]!!.jsonPrimitive.content,
    )

    @TestFactory
    fun chunker(): List<DynamicTest> = cases["chunk"]!!.jsonArray.mapIndexed { i, el ->
        val c = el.jsonObject
        val rec = record(c["record"]!!.jsonObject)
        val mode = c["mode"]!!.jsonPrimitive.content
        DynamicTest.dynamicTest("chunk #$i ${rec.id} $mode") {
            val want = c["chunks"]!!.jsonArray.map { chunk(it.jsonObject) }
            assertEquals(want, Chunker.chunkRecord(rec, mode, c["start_cid"]!!.jsonPrimitive.long))
        }
    }

    @TestFactory
    fun bm25(): List<DynamicTest> = cases["bm25"]!!.jsonArray.map { el ->
        val c = el.jsonObject
        val mode = c["mode"]!!.jsonPrimitive.content
        DynamicTest.dynamicTest("bm25 $mode") {
            val docs = c["docs"]!!.strs()
            val idx = Bm25Index(docs.map { ru.neurosteno.core.summary.SummaryText.terms(it, mode = mode) }, mode = mode)
            for (q in c["queries"]!!.jsonArray) {
                val qq = q.jsonObject["q"]!!.jsonPrimitive.content
                val want = q.jsonObject["scores"]!!.jsonArray.map { it.jsonPrimitive.double.toFloat() }
                val got = idx.scores(qq)
                assertEquals(want.size, got.size)
                val bad = want.indices.filter { want[it] != got[it] }
                assertTrue(bad.isEmpty(), "$mode '$qq': ${bad.size} из ${want.size} оценок отличаются, первые: ${bad.take(3).map { "${it}: ${want[it]} vs ${got[it]}" }}")
            }
        }
    }

    @TestFactory
    fun speakerQuery(): List<DynamicTest> = listOf(
        DynamicTest.dynamicTest("speaker parse + matches (${cases["speaker"]!!.jsonArray.size} кейсов)") {
            for (el in cases["speaker"]!!.jsonArray) {
                val c = el.jsonObject
                val names = c["names"].let { if (it == null || it is JsonNull) null else it.strs() }
                val q = c["query"]!!.jsonPrimitive.content
                val sq = SpeakerQueryParser.parse(q, names)
                assertEquals(c["clean"]!!.jsonPrimitive.content, sq.clean, "clean: $q / $names")
                assertEquals(c["numbers"]!!.ints().toSet(), sq.numbers, "numbers: $q")
                assertEquals(c["name_stems"]!!.jsonArray.map { it.strs() }.toSet(), sq.nameStems.toSet(), "name stems: $q / $names")
                for (m in c["matches"]!!.jsonArray) {
                    val mo = m.jsonObject
                    val rn = mo["names"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
                    assertEquals(mo["result"]!!.jsonPrimitive.boolean, SpeakerQueryParser.matches(sq, mo["speaker"]!!.jsonPrimitive.content, rn), "matches: $q ${mo["speaker"]}")
                }
            }
        },
    )

    private val index: Triple<Map<String, SearchRecord>, List<Chunk>, List<FloatArray>> by lazy {
        val si = cases["search_index"]!!.jsonObject
        val recs = si["records"]!!.jsonArray.map { record(it.jsonObject) }.associateBy { it.id }
        val chunks = si["chunks"]!!.jsonArray.map { chunk(it.jsonObject) }
        val vecs = si["vectors"]!!.jsonArray.map { v -> v.jsonArray.map { it.jsonPrimitive.double.toFloat() }.toFloatArray() }
        Triple(recs, chunks, vecs)
    }

    @TestFactory
    fun hybridSearch(): List<DynamicTest> = listOf(
        DynamicTest.dynamicTest("search RRF/soft/hard (${cases["search"]!!.jsonArray.size} запросов)") {
            val (recs, chunks, vecs) = index
            val dim = cases["search_index"]!!.jsonObject["dim"]!!.jsonPrimitive.int
            val store = BruteForceVectorStore(dim)
            for (rid in recs.keys) {
                val own = chunks.indices.filter { chunks[it].recordId == rid }
                if (own.isNotEmpty()) store.upsert(rid, own.map { chunks[it].id }, own.map { vecs[it] })
            }
            val searcher = HybridSearcher(chunks, chunks.map { ru.neurosteno.core.summary.SummaryText.terms(it.text, mode = "snow5") }, recs, store, SearchConfig(), denseCandidates = Int.MAX_VALUE / 2)
            var bad = 0
            val msgs = ArrayList<String>()
            for ((n, el) in cases["search"]!!.jsonArray.withIndex()) {
                val c = el.jsonObject
                val qv = c["qvec"]!!.jsonArray.map { it.jsonPrimitive.double.toFloat() }.toFloatArray()
                val rec = c["record"].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.content }
                val r = searcher.search(
                    c["query"]!!.jsonPrimitive.content, qv, c["k"]!!.jsonPrimitive.int, rec, c["mode"]!!.jsonPrimitive.content,
                    useBm25 = c["bm25"]!!.jsonPrimitive.boolean, useDense = c["dense"]!!.jsonPrimitive.boolean,
                )
                val want = c["hits"]!!.jsonArray.map { it.jsonObject["cid"]!!.jsonPrimitive.long to it.jsonObject["score"]!!.jsonPrimitive.double }
                val got = r.hits.map { it.chunk.id to it.score }
                val sameIds = want.map { it.first } == got.map { it.first }
                val sameScores = sameIds && want.indices.all { kotlin.math.abs(want[it].second - got[it].second) <= 1e-5 * maxOf(1.0, kotlin.math.abs(want[it].second)) }
                if (!sameScores) {
                    bad++
                    if (msgs.size < 5) msgs += "#$n '${c["query"]!!.jsonPrimitive.content}' rec=$rec ${c["mode"]!!.jsonPrimitive.content} bm25=${c["bm25"]} dense=${c["dense"]}: want $want got $got"
                }
            }
            assertEquals(0, bad, "расхождений: $bad\n" + msgs.joinToString("\n"))
        },
    )

    @TestFactory
    fun answer(): List<DynamicTest> = cases["answer"]!!.jsonArray.mapIndexed { i, el ->
        DynamicTest.dynamicTest("answer context+verify #$i") {
            val c = el.jsonObject
            val (recs, chunks, _) = index
            val byId = chunks.associateBy { it.id }
            val hits = c["hits"]!!.jsonArray.map { Hit(byId.getValue(it.jsonPrimitive.long), 1.0) }
            val ctx = AnswerBuilder.context(hits, recs, c["max_chars"]!!.jsonPrimitive.int)
            assertEquals(c["context"]!!.jsonPrimitive.content, ctx.text)
            val cites = c["cites"]!!.jsonArray.map { val a = it.jsonArray; Citation(a[0].jsonPrimitive.content, a[1].jsonPrimitive.long, a[2].jsonPrimitive.content) }
            assertEquals(cites, ctx.cites)
            for (v in c["verify"]!!.jsonArray) {
                val vo = v.jsonObject
                val out = AnswerBuilder.verify(vo["text"]!!.jsonPrimitive.content, ctx.cites)
                assertEquals(vo["out"]!!.jsonPrimitive.content, out.text, vo["text"]!!.jsonPrimitive.content)
                assertEquals(vo["bad"]!!.jsonPrimitive.int, out.badCites)
                assertEquals(vo["refused"]!!.jsonPrimitive.boolean, out.refused)
                assertEquals(vo["valid"]!!.jsonArray.map { val a = it.jsonArray; Citation(a[0].jsonPrimitive.content, a[1].jsonPrimitive.long, a[2].jsonPrimitive.content) }, out.validCites)
            }
        }
    }
}
