package ru.neurosteno.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.neurosteno.core.Transcript
import ru.neurosteno.core.TranscriptMeta
import ru.neurosteno.core.Utterance
import ru.neurosteno.core.Word

/**
 * Сохранённая расшифровка (файл `transcript.json` в каталоге записи истории). Собственные DTO вместо типов `:core`:
 * формат файла не зависит от внутренних классов и допускает новые поля (`ignoreUnknownKeys`).
 * Идентификаторы говорящих всегда канонические (`speaker_1..N` по порядку появления, см. TranscriptEditor.canonical),
 * имена хранятся отдельно -- в JSON по ТЗ они не попадают, только в TXT/SRT/VTT и расширенный JSON.
 */
@Serializable
data class WordDto(val t: String, val s: Long, val e: Long, val c: Float? = null)

@Serializable
data class UtteranceDto(
    val speaker: String,
    val start: Long,
    val end: Long,
    val text: String,
    val words: List<WordDto> = emptyList(),
)

@Serializable
data class MetaDto(
    val tier: String,
    val audioDurationMs: Long,
    val processingMs: Long,
    val stageMs: Map<String, Long> = emptyMap(),
    val models: Map<String, String> = emptyMap(),
    val appVersion: String = "",
    val language: String = "ru",
)

@Serializable
data class StoredTranscript(
    val utterances: List<UtteranceDto>,
    val meta: MetaDto,
    val speakerNames: Map<String, String> = emptyMap(),
    /** Пользователь правил расшифровку (слова у правленных реплик сброшены). */
    val edited: Boolean = false,
) {
    fun toTranscript(): Transcript = Transcript(
        utterances.map { u ->
            Utterance(u.speaker, u.start, u.end, u.text, u.words.map { Word(it.t, it.s, it.e, it.c) })
        },
        TranscriptMeta(
            tier = meta.tier, audioDurationMs = meta.audioDurationMs, processingMs = meta.processingMs,
            stageMs = meta.stageMs, models = meta.models, appVersion = meta.appVersion, language = meta.language,
        ),
    )

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(text: String): StoredTranscript = JSON.decodeFromString(serializer(), text)

        fun of(transcript: Transcript, speakerNames: Map<String, String> = emptyMap(), edited: Boolean = false) = StoredTranscript(
            utterances = transcript.utterances.map { u ->
                UtteranceDto(u.speakerId, u.startMs, u.endMs, u.text, u.words.map { WordDto(it.text, it.startMs, it.endMs, it.confidence) })
            },
            meta = transcript.meta.let {
                MetaDto(it.tier, it.audioDurationMs, it.processingMs, it.stageMs, it.models, it.appVersion, it.language)
            },
            speakerNames = speakerNames,
            edited = edited,
        )
    }
}
