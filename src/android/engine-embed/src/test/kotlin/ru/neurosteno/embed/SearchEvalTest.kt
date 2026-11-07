package ru.neurosteno.embed

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.neurosteno.core.TierConfig
import ru.neurosteno.core.search.BruteForceVectorStore
import ru.neurosteno.core.search.MemoryChunkStore
import ru.neurosteno.core.search.SearchEngine
import ru.neurosteno.core.search.SearchRecord
import ru.neurosteno.core.summary.SUtt

/**
 * Качество поиска Kotlin == Python на корпусе cats (40 записей, 56 вопросов): полный путь Kotlin -- нарезка, BM25, токенайзер +
 * ONNX Runtime, int8-хранилище с переранжированием, RRF, soft-фильтр говорящего -- против `bench_rag` (hit@5, MRR). Данные готовит
 * `scripts/gen_search_eval.py` в `$NEUROSTENO_ROOT/.cache/search-eval/`; нет данных/моделей -- тест пропускается. Печатает таблицу
 * (в т. ч. скорость эмбеддинга и поиска, RSS процесса) и пишет её в `/tmp/search_eval.txt`.
 */
class SearchEvalTest {
    private fun root(): File? = System.getenv("NEUROSTENO_ROOT")?.let(::File)

    private fun repoConfig(tier: String): TierConfig {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "config/tiers").isDirectory) d = d.parentFile
        return TierConfig.parse(File(d!!, "config/tiers/$tier.json").readText())
    }

    private fun records(arr: kotlinx.serialization.json.JsonArray) = arr.map { r ->
        val o = r.jsonObject
        SearchRecord(
            o["id"]!!.jsonPrimitive.content, o["title"]!!.jsonPrimitive.content,
            o["utts"]!!.jsonArray.map { u ->
                val x = u.jsonObject
                val sid = x["speaker"]!!.jsonPrimitive.content
                SUtt(x["idx"]!!.jsonPrimitive.int, sid, sid.substringAfter('_').toInt(), x["start_ms"]!!.jsonPrimitive.long, x["end_ms"]!!.jsonPrimitive.long, x["text"]!!.jsonPrimitive.content)
            },
            o["names"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
    }

    /** Хранилища для сравнения: полный перебор (int8 + f32) и, для 384-мерных векторов, ObjectBox HNSW. */
    private fun stores(dim: Int): List<Pair<String, ru.neurosteno.core.search.VectorStore>> = buildList {
        add("bruteforce" to BruteForceVectorStore(dim))
        if (dim == 384) runCatching { ru.neurosteno.store.ObjectBoxVectorStore.open(java.nio.file.Files.createTempDirectory("ob-eval").toFile()) }.onSuccess { add("objectbox" to it) }
    }

    private fun rssMb(): Long = runCatching {
        ProcessBuilder("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString()).start().inputStream.bufferedReader().readText().trim().toLong() / 1024
    }.getOrDefault(-1)

    @Test
    fun cats() {
        val base = root()
        val evalFile = base?.let { File(it, ".cache/search-eval/eval.json") }
        assumeTrue(evalFile != null && evalFile.isFile)
        assumeTrue(HostOrt.skipReason() ?: "", HostOrt.load())
        val models = File(base, ".cache/models")
        val doc = Json.parseToJsonElement(evalFile!!.readText()).jsonObject
        val report = StringBuilder()
        for ((name, tier) in listOf("user2-small" to "medium", "rubert-tiny-turbo" to "lite")) {
            val cfg = repoConfig(tier).search
            assumeTrue(File(models, "embed/$name/model.int8.onnx").isFile)
            val t0 = System.nanoTime()
            val emb = OrtTextEmbedder.createOrNull(models, cfg.embedder) { throw it }!!
            val loadMs = (System.nanoTime() - t0) / 1_000_000
            for (kind in listOf("asr", "ref")) {
                val corpus = doc["corpora"]!!.jsonObject[kind]!!.jsonObject
                val recs = records(corpus["records"]!!.jsonArray).associateBy { it.id }
                for (store in stores(cfg.embedder.dim)) {
                val engine = SearchEngine(cfg, MemoryChunkStore(), store.second, { emb }, { recs[it]?.names ?: emptyMap() })
                val ti = System.nanoTime()
                var chunks = 0
                for (r in recs.values) chunks += engine.index(r).chunks
                val indexMs = (System.nanoTime() - ti) / 1_000_000
                val py = doc["python"]!!.jsonObject["$kind/$name"]!!.jsonObject
                val perPy = py["per_question"]!!.jsonObject
                val rows = HashMap<String, MutableList<DoubleArray>>() // kind -> [hit1, hit5, recall5, mrr]
                val searchMs = ArrayList<Double>()
                var overlapSum = 0.0
                var n = 0
                for (qe in corpus["questions"]!!.jsonArray) {
                    val q = qe.jsonObject
                    val qid = q["id"]!!.jsonPrimitive.content
                    val qkind = q["kind"]!!.jsonPrimitive.content
                    val gold = q["gold"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.int }.toSet()
                    val record = if (qkind == "history") null else q["record"]!!.jsonPrimitive.content
                    val ts = System.nanoTime()
                    val hits = engine.search(q["text"]!!.jsonPrimitive.content, record, k = 10).hits
                    searchMs += (System.nanoTime() - ts) / 1e6
                    val rel = hits.map { h -> h.chunk.utts.any { (h.chunk.recordId to it) in gold } }
                    val first = rel.indexOfFirst { it }
                    val covered = hits.take(5).flatMap { h -> h.chunk.utts.map { h.chunk.recordId to it } }.toSet().intersect(gold)
                    val row = doubleArrayOf(
                        if (rel.firstOrNull() == true) 1.0 else 0.0, if (rel.take(5).any { it }) 1.0 else 0.0,
                        covered.size / maxOf(1, gold.size).toDouble(), if (first in 0..9) 1.0 / (first + 1) else 0.0,
                    )
                    rows.getOrPut("all") { ArrayList() } += row
                    rows.getOrPut(qkind) { ArrayList() } += row
                    val pyTop = perPy[qid]!!.jsonObject["top"]!!.jsonArray.map { it.jsonPrimitive.content }.take(5)
                    val ktTop = hits.take(5).map { "${it.chunk.recordId}:${it.chunk.utts.first()}:${it.chunk.utts.last()}" }
                    overlapSum += pyTop.intersect(ktTop.toSet()).size / maxOf(1, pyTop.size).toDouble()
                    n++
                }
                fun mean(k: String, i: Int) = rows[k]!!.map { it[i] }.average()
                val pa = py["agg"]!!.jsonObject["all"]!!.jsonObject
                searchMs.sort()
                val line = "%-18s %-3s %-10s chunks=%d  hit@5 kt=%.3f py=%.3f  MRR kt=%.3f py=%.3f  top5-overlap=%.3f | embed %.1f ms/окно (всего %.1f с), загрузка модели %d мс, поиск p50=%.1f мс p95=%.1f мс (с эмбеддингом запроса), RSS=%d МБ".format(
                    name, kind, store.first, chunks, mean("all", 1), pa["hit5"]!!.jsonPrimitive.double, mean("all", 3), pa["mrr"]!!.jsonPrimitive.double,
                    overlapSum / n, indexMs.toDouble() / chunks, indexMs / 1000.0, loadMs, searchMs[searchMs.size / 2], searchMs[(searchMs.size * 95) / 100], rssMb(),
                )
                println(line)
                report.appendLine(line)
                for (k in rows.keys.sorted()) {
                    val pk = py["agg"]!!.jsonObject[k]?.jsonObject ?: continue
                    report.appendLine("    %-8s n=%d hit@5 kt=%.3f py=%.3f  MRR kt=%.3f py=%.3f".format(k, rows[k]!!.size, mean(k, 1), pk["hit5"]!!.jsonPrimitive.double, mean(k, 3), pk["mrr"]!!.jsonPrimitive.double))
                }
                // допуск: качество Kotlin не хуже эталона более чем на 0.03 (int8-переранжирование и версии ORT дают микро-расхождения векторов)
                assertTrue(line, mean("all", 1) >= pa["hit5"]!!.jsonPrimitive.double - 0.03)
                assertTrue(line, mean("all", 3) >= pa["mrr"]!!.jsonPrimitive.double - 0.03)
                store.second.close()
                }
            }
            emb.close()
        }
        File("/tmp/search_eval.txt").writeText(report.toString())
    }
}
