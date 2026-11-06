package ru.neurosteno.app.ui

import java.util.Locale
import ru.neurosteno.core.Stage

/** Чистые функции форматирования (тестируются на JVM). */
object Format {
    fun clock(ms: Long): String {
        val s = maxOf(0L, ms) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%02d:%02d", m, sec)
    }

    fun stageTitle(stage: Stage): String = when (stage) {
        Stage.DECODE -> "Чтение аудио"
        Stage.DENOISE -> "Шумоподавление"
        Stage.VAD -> "Поиск речи"
        Stage.LID -> "Определение языка"
        Stage.ASR -> "Распознавание речи"
        Stage.DIARIZATION -> "Разделение говорящих"
        Stage.ALIGN -> "Сборка реплик"
        Stage.PUNCTUATION -> "Пунктуация"
        Stage.EXPORT -> "Подготовка результата"
    }

    fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"

    fun exportFileName(sourceName: String): String {
        val base = sourceName.substringBeforeLast('.', sourceName).ifBlank { "transcript" }
        return "$base.json"
    }
}
