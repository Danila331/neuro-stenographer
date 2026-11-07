package ru.neurosteno.core.search

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import ru.neurosteno.core.search.tokenizer.TextTokenizer

/**
 * Токенайзеры эмбеддеров == Python `tokenizers` бит в бит на 300+ строках (`tokenizer_cases.json`, `scripts/gen_tokenizer_cases.py`):
 * реальные окна корпуса, пробельные серии, добавленные токены, эмодзи, CJK, управляющие символы, усечение. Файлы `tokenizer.json`
 * берутся из общего кэша моделей (`$NEUROSTENO_ROOT/.cache/models/embed/<модель>/`); нет файлов -- тест пропускается.
 */
class TokenizerParityTest {
    private val cases by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/tokenizer_cases.json")!!.bufferedReader(Charsets.UTF_8).readText()).jsonObject
    }

    private fun modelsRoot(): File? =
        listOfNotNull(System.getenv("NEUROSTENO_ROOT"), System.getenv("ROOT")).map { File(it, ".cache/models/embed") }.firstOrNull { it.isDirectory }

    @TestFactory
    fun tokenizers(): List<DynamicTest> {
        val strings = cases["strings"]!!.jsonArray.map { it.jsonPrimitive.content }
        return cases["models"]!!.jsonObject.map { (name, m) ->
            DynamicTest.dynamicTest("$name: ${strings.size} строк x усечение 0/16/256") {
                val f = modelsRoot()?.let { File(it, "$name/tokenizer.json") }
                assumeTrue(f != null && f.isFile, "нет tokenizer.json модели $name в кэше")
                val tok = f!!.inputStream().use { TextTokenizer.load(it) }
                for ((lim, rows) in m.jsonObject["ids"]!!.jsonObject) {
                    val bad = ArrayList<String>()
                    rows.jsonArray.forEachIndexed { i, r ->
                        val want = r.jsonArray.map { it.jsonPrimitive.int }
                        val got = tok.encode(strings[i], lim.toInt()).toList()
                        if (want != got) bad += "#$i '${strings[i].take(60).replace("\n", "\\n")}': want ${want.take(24)} got ${got.take(24)}"
                    }
                    assertTrue(bad.isEmpty(), "$name lim=$lim: ${bad.size} расхождений, первые:\n" + bad.take(4).joinToString("\n"))
                }
                assertEquals(m.jsonObject["vocab"]!!.jsonPrimitive.int > 0, true)
            }
        }
    }
}
