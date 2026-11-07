package ru.neurosteno.app.data

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.neurosteno.app.TestData
import ru.neurosteno.app.edit.EditableTranscript

class ResultsAutosaveTest {
    private lateinit var dir: File
    private val source = TestData.dialog()
    private val editable = EditableTranscript.of(source)
    private val run = RunInfo(peakPssMb = 512, threads = 4, device = "Google Pixel 10", androidSdk = 37, totalRamMb = 11_800, appVersion = "0.1.0-medium")

    @Before fun setUp() { dir = Files.createTempDirectory("results").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    @Test
    fun writesResultsThenMarkerWithRunMeta() {
        val out = ResultsAutosave.write(dir, "встреча.m4a", editable, source, run)
        assertEquals(emptyList<String>(), out.problems)
        assertEquals(listOf("встреча.json", "встреча.full.json"), out.files)
        val marker = ResultsAutosave.writeDoneMarker(dir, "встреча.m4a", source, 3, run, "medium", listOf("предупреждение"), out, finishedAtMs = 42).getOrThrow()
        assertEquals("встреча.done.json", marker.name)
        val m = Json.parseToJsonElement(marker.readText()).jsonObject
        assertEquals("done", m["status"]!!.jsonPrimitive.content)
        assertEquals("512", m["peak_pss_mb"]!!.jsonPrimitive.content)
        assertEquals("4", m["threads"]!!.jsonPrimitive.content)
        assertEquals("Google Pixel 10", m["device_model"]!!.jsonPrimitive.content)
        assertEquals("37", m["android_sdk"]!!.jsonPrimitive.content)
        assertEquals("0.3", m["rtf"]!!.jsonPrimitive.content)
        assertEquals("3", m["utterances"]!!.jsonPrimitive.content)
        assertEquals("medium", m["model_set"]!!.jsonPrimitive.content)
        assertEquals(listOf("встреча.json", "встреча.full.json"), m["files"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("4000", m["stage_ms"]!!.jsonObject["asr"]!!.jsonPrimitive.content)
        // результаты на диске и валидны к моменту появления маркера
        assertTrue(File(dir, "встреча.json").readText().startsWith("{\"transcript\":["))
        assertFalse(File(dir, "встреча.done.json.tmp").exists())
    }

    @Test
    fun fullJsonMetaContainsPeakPssThreadsDevice() {
        ResultsAutosave.write(dir, "a.wav", editable, source, run)
        val meta = Json.parseToJsonElement(File(dir, "a.full.json").readText()).jsonObject["meta"]!!.jsonObject
        assertEquals("512", meta["peak_pss_mb"]!!.jsonPrimitive.content)
        assertEquals("4", meta["threads"]!!.jsonPrimitive.content)
        assertEquals("Google Pixel 10", meta["device_model"]!!.jsonPrimitive.content)
        assertEquals("37", meta["android_sdk"]!!.jsonPrimitive.content)
        assertEquals("0.1.0-medium", meta["app_version"]!!.jsonPrimitive.content)
        assertEquals("medium", meta["tier"]!!.jsonPrimitive.content) // прежние поля на месте
    }

    @Test
    fun failureOfOneFileDoesNotLoseTheOtherAndIsReportedNotSwallowed() {
        // на месте .full.json -- непустой каталог: запись файла невозможна
        File(dir, "a.full.json").apply { mkdirs() }.resolve("x").writeText("x")
        val out = ResultsAutosave.write(dir, "a.wav", editable, source, run)
        assertEquals(listOf("a.json"), out.files)
        assertEquals(1, out.problems.size)
        assertTrue(out.problems[0], out.problems[0].startsWith("a.full.json"))
        val marker = ResultsAutosave.writeDoneMarker(dir, "a.wav", source, 3, run, null, emptyList(), out).getOrThrow()
        val m = Json.parseToJsonElement(marker.readText()).jsonObject
        assertEquals(1, m["problems"]!!.jsonArray.size)
        // строка logcat формируется и при проблемах записи
        val line = ResultsAutosave.doneLine("a.wav", source, run, emptyList(), out.problems)
        assertTrue(line, line.startsWith("done a.wav:") && line.contains("write_problems="))
    }

    @Test
    fun doneLineKeepsLegacyPrefixAndCarriesB33Fields() {
        val line = ResultsAutosave.doneLine("cats.wav", source, run, listOf("w"), emptyList())
        assertTrue(line, line.startsWith("done cats.wav: duration=20000 ms processing=6000 ms rtf=0.300"))
        assertTrue(line, line.contains("peak_pss_mb=512") && line.contains("threads=4") && line.contains("device=Google Pixel 10") && line.contains("sdk=37"))
        assertTrue(line, line.contains("app=0.1.0-medium") && line.contains("warnings=[w]"))
    }

    @Test
    fun resultsDirFallsBackWhenStaleDirIsNotWritable() {
        // воспроизведение B32: `results` остался от другой установки (другой uid) -- писать в него нельзя
        val external = File(dir, "ext").apply { mkdirs() }
        val internal = File(dir, "int").apply { mkdirs() }
        val stale = File(external, "results").apply { mkdirs() }
        assertEquals(stale, ResultsAutosave.resultsDir(external, internal, 10216)) // пока доступен -- он и берётся
        assertTrue(stale.setWritable(false))
        try {
            if (stale.canWrite()) return // запуск от root: ограничение прав не работает, нечего проверять
            val chosen = ResultsAutosave.resultsDir(external, internal, 10216)
            assertEquals(File(external, "results-10216"), chosen)
            assertTrue(ResultsAutosave.write(chosen, "a.wav", editable, source, run).problems.isEmpty())
            // и внешняя папка целиком недоступна -> внутренняя
            val noExternal = ResultsAutosave.resultsDir(null, internal, 1)
            assertEquals(File(internal, "results"), noExternal)
        } finally {
            stale.setWritable(true)
        }
    }

    @Test
    fun doneLineMentionsResultsDir() {
        val line = ResultsAutosave.doneLine("a.wav", source, run, emptyList(), emptyList(), File("/sdcard/x/results"))
        assertTrue(line, line.contains("results=/sdcard/x/results"))
    }

    @Test
    fun clearMarkersRemovesStaleOnesOnly() {
        File(dir, "a.done.json").writeText("{}"); File(dir, "a.error.json").writeText("{}"); File(dir, "a.json").writeText("{}"); File(dir, "b.done.json").writeText("{}")
        ResultsAutosave.clearMarkers(dir, "a.wav")
        assertFalse(File(dir, "a.done.json").exists())
        assertFalse(File(dir, "a.error.json").exists())
        assertTrue(File(dir, "a.json").exists())
        assertTrue(File(dir, "b.done.json").exists())
    }

    @Test
    fun errorMarkerCarriesMessage() {
        val f = ResultsAutosave.writeErrorMarker(dir, "a.wav", "Не хватило памяти", run).getOrThrow()
        assertEquals("a.error.json", f.name)
        val m = Json.parseToJsonElement(f.readText()).jsonObject
        assertEquals("error", m["status"]!!.jsonPrimitive.content)
        assertEquals("Не хватило памяти", m["error"]!!.jsonPrimitive.content)
    }

    // ---- B58 (P1): маркер done только после успешной записи обязательного JSON ----

    @Test
    fun publishWritesResultsThenDoneMarkerAndNoErrorMarker() {
        File(dir, "a.error.json").writeText("{}") // залежавшийся маркер прошлого запуска
        val p = ResultsAutosave.publish(dir, "a.wav", editable, source, run, "medium", emptyList(), finishedAtMs = 7)
        assertTrue(p is ResultsAutosave.Published.Done)
        assertTrue(File(dir, "a.json").isFile && File(dir, "a.full.json").isFile && File(dir, "a.done.json").isFile)
        assertFalse(File(dir, "a.error.json").exists())
        assertEquals("done", Json.parseToJsonElement(File(dir, "a.done.json").readText()).jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun requiredJsonNotWrittenMeansErrorMarkerNotDone() {
        // на месте обязательного a.json -- непустой каталог: записать файл нельзя (как «нет места»)
        File(dir, "a.json").apply { mkdirs() }.resolve("x").writeText("x")
        File(dir, "a.done.json").writeText("""{"status":"done","old":true}""") // маркер прошлого запуска
        val p = ResultsAutosave.publish(dir, "a.wav", editable, source, run, "medium", emptyList())
        assertTrue(p is ResultsAutosave.Published.Failed)
        assertFalse("done-маркера быть не должно", File(dir, "a.done.json").exists())
        val err = Json.parseToJsonElement(File(dir, "a.error.json").readText()).jsonObject
        assertEquals("error", err["status"]!!.jsonPrimitive.content)
        assertTrue(err["error"]!!.jsonPrimitive.content, err["error"]!!.jsonPrimitive.content.contains("a.json"))
    }

    @Test
    fun oldResultWithTheSameNameIsNeverPassedOffAsNew() {
        // прошлый запуск оставил рабочий результат под тем же именем
        ResultsAutosave.publish(dir, "a.wav", editable, source, run, null, emptyList())
        val old = File(dir, "a.json").readText()
        assertTrue(old.isNotEmpty())
        // новый запуск: запись a.json невозможна. Удалить старый файл нельзя только если на его месте каталог, поэтому имитируем иначе:
        // каталог для .tmp -- запись tmp падает, старый a.json обязан исчезнуть заранее
        File(dir, "a.json.tmp").mkdirs().also { File(dir, "a.json.tmp/x").writeText("x") }
        val p = ResultsAutosave.publish(dir, "a.wav", editable, source, run, null, emptyList())
        assertTrue(p is ResultsAutosave.Published.Failed)
        assertFalse("старый a.json не должен остаться за новый", File(dir, "a.json").exists())
        assertFalse(File(dir, "a.full.json").exists() && File(dir, "a.done.json").exists())
        assertTrue(File(dir, "a.error.json").isFile)
    }

    @Test
    fun failureOfOptionalFullJsonKeepsDoneButReportsProblem() {
        File(dir, "a.full.json").apply { mkdirs() }.resolve("x").writeText("x")
        val p = ResultsAutosave.publish(dir, "a.wav", editable, source, run, null, emptyList())
        assertTrue(p is ResultsAutosave.Published.Done)
        assertEquals(1, p.outcome.problems.size)
        val m = Json.parseToJsonElement(File(dir, "a.done.json").readText()).jsonObject
        assertEquals(1, m["problems"]!!.jsonArray.size)
    }

    @Test
    fun clearStaleRemovesMarkersAndResultsOfThatNameOnly() {
        for (n in listOf("a.json", "a.full.json", "a.done.json", "a.error.json", "b.json", "b.done.json")) File(dir, n).writeText("{}")
        ResultsAutosave.clearStale(dir, "a.wav")
        assertEquals(setOf("b.json", "b.done.json"), dir.list()!!.toSet())
    }
}
