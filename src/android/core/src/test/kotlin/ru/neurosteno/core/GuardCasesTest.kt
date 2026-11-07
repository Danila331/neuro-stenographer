package ru.neurosteno.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Кросс-проверка Kotlin <-> Python для `norm` и guard (docs/team/ARCHITECTURE_TEAM.md §6) на `src/eval/refpipe/tests/guard_cases.json`.
 * Нет файла -- тест пропускается с пояснением.
 */
class GuardCasesTest {
    private fun file() = File(TestPaths.repoRoot, "src/eval/refpipe/tests/guard_cases.json")

    private fun JsonObject.str(k: String) = (this[k] as JsonPrimitive).content

    @TestFactory
    fun `kotlin guard matches python reference cases`(): List<DynamicTest> {
        val f = file()
        assumeTrue(f.isFile) { "ПРОПУЩЕНО: нет ${f.path} -- parity-кейсы guard приходят из ветки refpipe" }
        val root = Json.parseToJsonElement(f.readText()).jsonObject
        val norm = root["norm"]!!.jsonArray.mapIndexed { i, el ->
            val o = el.jsonObject
            DynamicTest.dynamicTest("norm[$i] '${o.str("in")}'") { assertEquals(o.str("out"), TextGuard.norm(o.str("in"))) }
        }
        val guard = root["guard"]!!.jsonArray.mapIndexed { i, el ->
            val o = el.jsonObject
            DynamicTest.dynamicTest("guard[$i] '${o.str("input")}' -> '${o.str("output")}'") {
                val accepted = o["accepted"]!!.let { (it as JsonPrimitive).boolean }
                assertEquals(o.str("text"), TextGuard.guardText(o.str("input"), o.str("output")))
                // accepted <=> результат совпал с (stripped) выходом модели, а не с fallback
                val input = o.str("input")
                val wasAccepted = o.str("output").isNotBlank() && TextGuard.sameWords(input, o.str("output"))
                assertEquals(accepted, wasAccepted)
            }
        }
        return norm + guard
    }
}
