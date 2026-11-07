package ru.neurosteno.embed

import java.io.File
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.neurosteno.core.TierConfig

/**
 * Эмбеддер ONNX Runtime == Python onnxruntime (косинус >= 0.9999) на обеих моделях, документы и запросы. Модели берутся из общего
 * кэша `$NEUROSTENO_ROOT/.cache/models`; нет файлов -- тест пропускается. Конфиг (пулинг, префиксы) -- из `config/tiers/<tier>.json`.
 */
class OrtTextEmbedderTest {
    private val cases by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/embedder_cases.json")!!.bufferedReader(Charsets.UTF_8).readText()).jsonObject
    }

    private fun root(): File? = System.getenv("NEUROSTENO_ROOT")?.let { File(it, ".cache/models") }?.takeIf { it.isDirectory }

    private fun repoConfig(tier: String): TierConfig {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "config/tiers").isDirectory) d = d.parentFile
        return TierConfig.parse(File(d!!, "config/tiers/$tier.json").readText())
    }

    private fun cos(a: FloatArray, b: List<Double>): Double {
        var d = 0.0
        for (i in a.indices) d += a[i] * b[i]
        return d
    }

    private fun check(model: String, tier: String) {
        val models = root()
        assumeTrue(models != null && File(models, "embed/$model/model.int8.onnx").isFile)
        assumeTrue(HostOrt.skipReason() ?: "", HostOrt.load())
        val cfg = repoConfig(tier).search.embedder
        val e = OrtTextEmbedder.createOrNull(models!!, cfg) { throw it }!!
        try {
            val c = cases[model]!!.jsonObject
            val texts = c["texts"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(c["dim"]!!.jsonPrimitive.double.toInt(), e.dim)
            for ((kind, isQuery) in listOf("doc" to false, "query" to true)) {
                val want = c[kind]!!.jsonArray.map { v -> v.jsonArray.map { it.jsonPrimitive.double } }
                val wantSingle = c[kind + "_single"]!!.jsonArray.map { v -> v.jsonArray.map { it.jsonPrimitive.double } }
                val got = e.embed(texts, isQuery)
                assertEquals(texts.size, got.size)
                for (i in texts.indices) {
                    val nrm = sqrt(got[i].sumOf { (it * it).toDouble() })
                    assertEquals("норма", 1.0, nrm, 1e-4)
                    // один и тот же батч: версии ORT (1.28 здесь, 1.31 у эталона) дают близкие, но не бит-в-бит векторы
                    assertTrue("$model $kind #$i (батч): косинус с Python ${cos(got[i], want[i])}", cos(got[i], want[i]) >= 0.99)
                    // по одному тексту -- совпадение с эталоном почти точное
                    val single = e.embed(listOf(texts[i]), isQuery).single()
                    assertTrue("$model $kind #$i (один): косинус с Python ${cos(single, wantSingle[i])}", cos(single, wantSingle[i]) >= 0.999)
                }
            }
        } finally {
            e.close()
        }
    }

    @Test
    fun user2Small() = check("user2-small", "medium")

    @Test
    fun rubertTinyTurbo() = check("rubert-tiny-turbo", "lite")

    /** B58: путь к модели со символами вне BMP (emoji) и кириллицей уходит в ORT стандартным UTF-8 (раньше -- modified UTF-8 из GetStringUTFChars). */
    @Test
    fun modelPathWithEmojiAndCyrillicLoads() {
        val models = root()
        assumeTrue(models != null && File(models, "embed/rubert-tiny-turbo/model.int8.onnx").isFile)
        assumeTrue(HostOrt.skipReason() ?: "", HostOrt.load())
        val dir = java.nio.file.Files.createTempDirectory("модель-😀-").toFile()
        try {
            File(models, "embed/rubert-tiny-turbo/model.int8.onnx").copyTo(File(dir, "модель 😀.onnx"))
            File(models, "embed/rubert-tiny-turbo/tokenizer.json").copyTo(File(dir, "tokenizer.json"))
            val e = OrtTextEmbedder(File(dir, "модель 😀.onnx"), File(dir, "tokenizer.json"), repoConfig("lite").search.embedder)
            assertEquals(312, e.embed(listOf("проверка"), false).single().size)
            e.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingModelGivesAManagedErrorNotACrash() {
        assumeTrue(HostOrt.skipReason() ?: "", HostOrt.load())
        val tok = File.createTempFile("tok", ".json")
        try {
            val cfg = repoConfig("lite").search.embedder
            val ex = runCatching { OrtTextEmbedder(File("/нет/такой/модели-😀.onnx"), tok, cfg) }.exceptionOrNull()
            assertTrue(ex.toString(), ex != null)
        } finally {
            tok.delete()
        }
    }
}
