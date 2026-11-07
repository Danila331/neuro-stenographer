package ru.neurosteno.core

import java.io.OutputStream
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Сериализация расшифровки.
 *
 * [toJson] -- ровно формат ТЗ (docs/team/ARCHITECTURE_TEAM.md §1), записывается вручную ради гарантии порядка ключей:
 * `{"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"..."}]}`.
 * Компактно (как в примере ТЗ), UTF-8 без BOM, кириллица без `\u`-экранирования, времена -- целые.
 *
 * Перед записью реплики приводятся к инвариантам контракта ([normalize]):
 * пустые тексты отбрасываются, текст триммится (переводы строк -> пробел), времена зажимаются в
 * `0 <= start < end <= durationMs`, сортировка по (start, end), спикеры перенумеровываются
 * в `speaker_1..N` по первому появлению.
 */
object TranscriptJson {

    data class Item(val speakerId: String, val startMs: Long, val endMs: Long, val text: String)

    /** Приводит реплики к инвариантам контракта; идемпотентна. */
    fun normalize(utterances: List<Utterance>, durationMs: Long): List<Utterance> {
        if (durationMs <= 0) return emptyList()
        val cleaned = utterances.mapNotNull { u ->
            val text = cleanText(u.text)
            if (text.isEmpty()) return@mapNotNull null
            val start = u.startMs.coerceIn(0L, durationMs - 1)
            val end = maxOf(u.endMs, start + 1).coerceAtMost(durationMs)
            u.copy(startMs = start, endMs = end, text = text)
        }.sortedWith(compareBy({ it.startMs }, { it.endMs }))
        val ids = LinkedHashMap<String, String>()
        return cleaned.map { u ->
            val id = ids.getOrPut(u.speakerId) { speakerLabel(ids.size + 1) }
            u.copy(speakerId = id)
        }
    }

    fun toJson(utterances: List<Utterance>, durationMs: Long): String {
        val items = normalize(utterances, durationMs)
        val sb = StringBuilder(64 + items.size * 96)
        sb.append("{\"transcript\":[")
        items.forEachIndexed { i, u ->
            if (i > 0) sb.append(',')
            sb.append("{\"speaker_id\":")
            appendString(sb, u.speakerId)
            sb.append(",\"start_ms\":").append(u.startMs)
            sb.append(",\"end_ms\":").append(u.endMs)
            sb.append(",\"text\":")
            appendString(sb, u.text)
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    fun toJson(transcript: Transcript): String =
        toJson(transcript.utterances, transcript.meta.audioDurationMs)

    fun writeTo(out: OutputStream, transcript: Transcript) {
        out.write(toJson(transcript).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Разбор JSON формата ТЗ (для тестов/round-trip и импорта сохранённых результатов). */
    fun parse(text: String): List<Item> {
        val root = Json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("Ожидался JSON-объект")
        val arr = root["transcript"] as? JsonArray
            ?: throw IllegalArgumentException("Нет ключа transcript")
        return arr.map { el ->
            val o = el as JsonObject
            Item(
                speakerId = (o["speaker_id"] as JsonPrimitive).content,
                startMs = (o["start_ms"] as JsonPrimitive).content.toLong(),
                endMs = (o["end_ms"] as JsonPrimitive).content.toLong(),
                text = (o["text"] as JsonPrimitive).content,
            )
        }
    }

    // ---- расширенный экспорт (*.full.json) ----

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** Расширенный JSON: слова с временем и confidence, версия приложения, tier, модели, время по стадиям, RTF. */
    fun toFullJson(transcript: Transcript): String {
        val meta = transcript.meta
        val items = normalize(transcript.utterances, meta.audioDurationMs)
        val root = buildJsonObject {
            put("transcript", buildJsonArray {
                items.forEach { u ->
                    add(buildJsonObject {
                        put("speaker_id", u.speakerId)
                        put("start_ms", u.startMs)
                        put("end_ms", u.endMs)
                        put("text", u.text)
                        put("words", buildJsonArray {
                            u.words.forEach { w ->
                                add(buildJsonObject {
                                    put("text", w.text)
                                    put("start_ms", w.startMs)
                                    put("end_ms", w.endMs)
                                    put("confidence", w.confidence?.let { JsonPrimitive(it) } ?: JsonNull)
                                })
                            }
                        })
                    })
                }
            })
            put("meta", buildJsonObject {
                put("app_version", meta.appVersion)
                put("tier", meta.tier)
                put("language", meta.language)
                put("audio_duration_ms", meta.audioDurationMs)
                put("processing_ms", meta.processingMs)
                put("rtf", Math.round(meta.rtf * 1000.0) / 1000.0)
                put("stage_ms", buildJsonObject { meta.stageMs.forEach { (k, v) -> put(k, v) } })
                put("models", buildJsonObject { meta.models.forEach { (k, v) -> put(k, v) } })
            })
        }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    // ---- внутреннее ----

    private fun cleanText(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                Character.isHighSurrogate(ch) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    sb.append(ch).append(s[i + 1]); i++
                }
                Character.isSurrogate(ch) -> sb.append('\uFFFD')
                ch == '\n' || ch == '\r' || ch == '\t' || ch == '\u00A0' || ch == '\u2028' || ch == '\u2029' -> sb.append(' ')
                ch.code < 0x20 || ch.code == 0x7F -> Unit // прочие управляющие -- выбросить
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString().trim().replace(MULTI_SPACE, " ")
    }

    private val MULTI_SPACE = Regex(" {2,}")

    /** JSON-строка: экранируются только `"`, `\` и управляющие < 0x20; всё остальное (кириллица, эмодзи) -- как есть. */
    private fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch.code < 0x20 -> sb.append("\\u").append(String.format(Locale.ROOT, "%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }
}
