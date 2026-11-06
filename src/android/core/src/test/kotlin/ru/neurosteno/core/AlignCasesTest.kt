package ru.neurosteno.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Кросс-проверка Kotlin <-> Python: читает кейсы Aligner из эталона (`src/eval/refpipe/tests/align_cases.json`)
 * и требует побайтного совпадения реплик. Если файла нет (ветка refpipe ещё не влита) -- тест пропускается.
 *
 * Ожидаемый формат (парсер терпим к синонимам ключей):
 * ```
 * [ { "name": "...",
 *     "align": {"max_gap_ms":1500,"min_island_words":1,"min_island_ms":400},   // опционально
 *     "words": [{"text":"привет","start_ms":0,"end_ms":500}],
 *     "turns": [{"speaker":0,"start_ms":0,"end_ms":1000}],
 *     "expected": [{"speaker_id":"speaker_1","start_ms":0,"end_ms":500,"text":"привет"}] } ]
 * ```
 * Допустима обёртка `{"cases":[...]}`; времена -- целые миллисекунды.
 */
class AlignCasesTest {

    private fun casesFile(): File {
        System.getProperty("neurosteno.alignCases")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        return File(TestPaths.repoRoot, "src/eval/refpipe/tests/align_cases.json")
    }

    @TestFactory
    fun `kotlin aligner matches python reference cases`(): List<DynamicTest> {
        val f = casesFile()
        assumeTrue(f.isFile) {
            "ПРОПУЩЕНО: нет ${f.path} -- кейсы Aligner появятся после слияния ветки w1/refpipe; " +
                "проверка совпадения Kotlin и Python выполнится автоматически"
        }
        val root: JsonElement = Json.parseToJsonElement(f.readText())
        val cases: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["cases"] ?: error("в ${f.name} нет массива cases")).jsonArray
            else -> error("неожиданный формат ${f.name}")
        }
        return cases.mapIndexed { i, el ->
            val c = el.jsonObject
            val name = (c["name"] ?: c["id"])?.let { (it as JsonPrimitive).content } ?: "case_$i"
            DynamicTest.dynamicTest(name) { runCase(c) }
        }
    }

    private fun runCase(c: JsonObject) {
        val cfgObj = (c["align"] ?: c["config"] ?: c["params"]) as? JsonObject
        val d = AlignConfig()
        val cfg = AlignConfig(
            maxGapMs = cfgObj?.long("max_gap_ms") ?: d.maxGapMs,
            minIslandWords = cfgObj?.long("min_island_words")?.toInt() ?: d.minIslandWords,
            minIslandMs = cfgObj?.long("min_island_ms") ?: d.minIslandMs,
        )
        val words = (c["words"] as JsonArray).map {
            val o = it.jsonObject
            Word(
                text = ((o["text"] ?: o["word"]) as JsonPrimitive).content,
                startMs = o.long("start_ms", "start")!!,
                endMs = o.long("end_ms", "end")!!,
            )
        }
        val turns = ((c["turns"] ?: JsonArray(emptyList())) as JsonArray).map {
            val o = it.jsonObject
            SpeakerTurn(
                speaker = o.long("speaker", "speaker_id")!!.toInt(),
                startMs = o.long("start_ms", "start")!!,
                endMs = o.long("end_ms", "end")!!,
            )
        }
        val expected = ((c["expected"] ?: c["utterances"] ?: c["transcript"]) as JsonArray).map {
            val o = it.jsonObject
            listOf(
                (o["speaker_id"] as JsonPrimitive).content,
                o.long("start_ms")!!.toString(),
                o.long("end_ms")!!.toString(),
                (o["text"] as JsonPrimitive).content,
            )
        }
        val actual = Aligner(cfg).align(words, turns).map {
            listOf(it.speakerId, it.startMs.toString(), it.endMs.toString(), it.text)
        }
        assertEquals(expected, actual)
    }

    private fun JsonObject.long(vararg keys: String): Long? = keys.firstNotNullOfOrNull { k ->
        (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.removePrefix("speaker_")?.toLongOrNull() }
    }
}
