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
        Stage.DECODE -> "Декодирование"
        Stage.DENOISE -> "Шумоподавление"
        Stage.VAD -> "Поиск речи"
        Stage.LID -> "Определение языка"
        Stage.ASR -> "Распознавание"
        Stage.DIARIZATION -> "Разделение говорящих"
        Stage.ALIGN -> "Сборка реплик"
        Stage.PUNCTUATION -> "Пунктуация"
        Stage.EXPORT -> "Сохранение результата"
    }

    /** «1 ч 02 мин», «12 мин 05 с», «45 с» -- оставшееся время и длительности в интерфейсе. */
    fun duration(ms: Long): String {
        val s = maxOf(0L, ms + 500) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> String.format(Locale.ROOT, "%d ч %02d мин", h, m)
            m > 0 -> String.format(Locale.ROOT, "%d мин %02d с", m, sec)
            else -> "$sec с"
        }
    }

    /** Размер файла: «1,6 ГБ», «224 МБ», «12 КБ». */
    fun bytes(n: Long): String {
        val kb = 1024.0
        return when {
            n >= kb * kb * kb -> String.format(Locale("ru"), "%.1f ГБ", n / (kb * kb * kb))
            n >= kb * kb -> String.format(Locale("ru"), "%.0f МБ", n / (kb * kb))
            n >= kb -> String.format(Locale("ru"), "%.0f КБ", n / kb)
            else -> "$n Б"
        }
    }

    fun rtf(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    /** Склонение по числу: plural(2, "реплика", "реплики", "реплик"). */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val m100 = n % 100
        val m10 = n % 10
        val word = when {
            m100 in 11..14 -> many
            m10 == 1 -> one
            m10 in 2..4 -> few
            else -> many
        }
        return "$n $word"
    }

    fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"

    fun exportFileName(sourceName: String): String {
        val base = sourceName.substringBeforeLast('.', sourceName).ifBlank { "transcript" }
        return "$base.json"
    }
}
