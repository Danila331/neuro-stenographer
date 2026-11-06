package ru.neurosteno.core

import java.util.Locale

/** Экспортёр расшифровки в текстовый формат. Запись в файл (SAF) -- на стороне приложения. */
interface TranscriptExporter {
    val id: String
    val fileExtension: String
    val mimeType: String
    fun export(transcript: Transcript): String
}

/** Подпись говорящего в субтитрах/тексте: можно подставить переименованные имена из UI. */
typealias SpeakerNames = (speakerId: String) -> String

private val defaultNames: SpeakerNames = { it }

private fun lines(transcript: Transcript): List<Utterance> =
    TranscriptJson.normalize(transcript.utterances, transcript.meta.audioDurationMs)

/** JSON строго по ТЗ. */
object JsonExporter : TranscriptExporter {
    override val id = "json"
    override val fileExtension = "json"
    override val mimeType = "application/json"
    override fun export(transcript: Transcript): String = TranscriptJson.toJson(transcript)
}

/** Расширенный JSON (`*.full.json`). */
object FullJsonExporter : TranscriptExporter {
    override val id = "full_json"
    override val fileExtension = "full.json"
    override val mimeType = "application/json"
    override fun export(transcript: Transcript): String = TranscriptJson.toFullJson(transcript)
}

class SrtExporter(private val names: SpeakerNames = defaultNames) : TranscriptExporter {
    override val id = "srt"
    override val fileExtension = "srt"
    override val mimeType = "application/x-subrip"
    override fun export(transcript: Transcript): String {
        val sb = StringBuilder()
        lines(transcript).forEachIndexed { i, u ->
            sb.append(i + 1).append('\n')
            sb.append(timestamp(u.startMs, ',')).append(" --> ").append(timestamp(u.endMs, ',')).append('\n')
            sb.append(names(u.speakerId)).append(": ").append(u.text).append("\n\n")
        }
        return sb.toString()
    }
}

class VttExporter(private val names: SpeakerNames = defaultNames) : TranscriptExporter {
    override val id = "vtt"
    override val fileExtension = "vtt"
    override val mimeType = "text/vtt"
    override fun export(transcript: Transcript): String {
        val sb = StringBuilder("WEBVTT\n\n")
        lines(transcript).forEachIndexed { i, u ->
            sb.append(i + 1).append('\n')
            sb.append(timestamp(u.startMs, '.')).append(" --> ").append(timestamp(u.endMs, '.')).append('\n')
            sb.append("<v ").append(names(u.speakerId).replace('>', ' ')).append('>')
                .append(escapeVtt(u.text)).append("\n\n")
        }
        return sb.toString()
    }

    private fun escapeVtt(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

class TxtExporter(private val names: SpeakerNames = defaultNames) : TranscriptExporter {
    override val id = "txt"
    override val fileExtension = "txt"
    override val mimeType = "text/plain"
    override fun export(transcript: Transcript): String {
        val sb = StringBuilder()
        lines(transcript).forEach { u ->
            sb.append('[').append(clock(u.startMs)).append("] ")
                .append(names(u.speakerId)).append(": ").append(u.text).append('\n')
        }
        return sb.toString()
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%02d:%02d", m, sec)
    }
}

internal fun timestamp(ms: Long, millisSeparator: Char): String {
    val total = maxOf(0L, ms)
    val h = total / 3_600_000
    val m = (total % 3_600_000) / 60_000
    val s = (total % 60_000) / 1000
    val milli = total % 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", h, m, s, millisSeparator, milli)
}
