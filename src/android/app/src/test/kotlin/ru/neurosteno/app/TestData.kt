package ru.neurosteno.app

import ru.neurosteno.core.Transcript
import ru.neurosteno.core.TranscriptMeta
import ru.neurosteno.core.Utterance
import ru.neurosteno.core.Word

/** Общие данные для тестов. */
object TestData {
    fun utt(speaker: Int, start: Long, end: Long, text: String) =
        Utterance("speaker_$speaker", start, end, text, text.split(' ').mapIndexed { i, w -> Word(w, start + i * 100L, start + i * 100L + 90) })

    val meta = TranscriptMeta(
        tier = "medium", audioDurationMs = 20_000, processingMs = 6_000,
        stageMs = mapOf("asr" to 4000L, "total" to 6000L), models = mapOf("asr" to "gigaam"), appVersion = "test",
    )

    /** Диалог из трёх реплик: speaker_2 говорит первым (до перенумерации). */
    fun dialog() = Transcript(
        listOf(
            utt(2, 1_000, 3_000, "добрый день"),
            utt(1, 3_500, 6_000, "здравствуйте коллеги"),
            utt(2, 6_500, 9_000, "начнём совещание"),
        ),
        meta,
    )
}
