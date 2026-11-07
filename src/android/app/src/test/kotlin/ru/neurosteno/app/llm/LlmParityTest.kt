package ru.neurosteno.app.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.neurosteno.core.TextGuard

/**
 * Порт 1:1 против эталона llm-bench (`bench_llm/android_flow.py`): ожидаемые значения в `llm_cases.json` посчитал сам Python-код
 * (`app/tools/gen_llm_cases.py`), тут Kotlin обязан дать ровно те же строки.
 */
class LlmParityTest {
    private val cases: JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/llm_cases.json")!!.bufferedReader(Charsets.UTF_8).readText(),
    ).jsonObject

    private fun strings(a: JsonArray) = a.map { it.jsonPrimitive.content }

    @Test
    fun splitSectionsMatchesReference() {
        val list = cases.getValue("split").jsonArray
        assertTrue(list.size >= 6)
        for (c in list) {
            val o = c.jsonObject
            val expected = o.getValue("expected").jsonObject.mapValues { strings(it.value.jsonArray) }
            assertEquals(o.getValue("text").jsonPrimitive.content, expected, ProtocolMerge.splitSections(o.getValue("text").jsonPrimitive.content))
        }
    }

    @Test
    fun mergeProtocolsMatchesReference() {
        for (c in cases.getValue("merge").jsonArray) {
            val o = c.jsonObject
            val parts = strings(o.getValue("parts").jsonArray)
            assertEquals(parts.toString(), o.getValue("expected").jsonPrimitive.content, ProtocolMerge.mergeProtocols(parts))
        }
    }

    @Test
    fun filterQuotesMatchesReference() {
        for (c in cases.getValue("filter").jsonArray) {
            val o = c.jsonObject
            val norm = ProtocolMerge.normTranscript(strings(o.getValue("transcript").jsonArray))
            assertEquals(o.getValue("text").jsonPrimitive.content, o.getValue("expected").jsonPrimitive.content, ProtocolMerge.filterQuotes(o.getValue("text").jsonPrimitive.content, norm))
        }
    }

    @Test
    fun contentBudgetChunkingAndSelectionMatchReference() {
        for (c in cases.getValue("budget").jsonArray) {
            val o = c.jsonObject
            assertEquals(o.getValue("expected").jsonPrimitive.int, PromptBuilder.contentBudgetChars(o.getValue("n_ctx").jsonPrimitive.int, o.getValue("max_new").jsonPrimitive.int, o.getValue("system_chars").jsonPrimitive.int))
        }
        for (c in cases.getValue("chunk").jsonArray) {
            val o = c.jsonObject
            assertEquals(strings(o.getValue("expected").jsonArray), PromptBuilder.chunk(strings(o.getValue("lines").jsonArray), o.getValue("budget").jsonPrimitive.int))
        }
        for (c in cases.getValue("select").jsonArray) {
            val o = c.jsonObject
            assertEquals(
                strings(o.getValue("expected").jsonArray),
                PromptBuilder.selectRelevant(strings(o.getValue("chunks").jsonArray), o.getValue("question").jsonPrimitive.content, o.getValue("budget").jsonPrimitive.int),
            )
        }
    }

    @Test
    fun normMatchesGuardNormOnQuotes() {
        // фильтр опирается на norm из ARCHITECTURE §6: регистр, ё/е, знаки и повторные пробелы не мешают совпадению
        assertEquals("елка стоит во дворе", TextGuard.norm("«Ёлка  стоит, во дворе!»"))
    }
}
