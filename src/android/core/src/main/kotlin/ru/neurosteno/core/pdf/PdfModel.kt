package ru.neurosteno.core.pdf

/** Шрифты PDF (в приложении -- вшитые Unbounded / Golos Text / JetBrains Mono): заголовки, обычный и жирный текст, моно для таймкодов. */
enum class PdfFont { TITLE, HEADING, BODY, BOLD, MONO, SMALL }

/** Измеритель текста: Android -- `Paint.measureText` вшитых шрифтов, тесты -- фейк с постоянной шириной символа. */
fun interface PdfMetrics {
    fun width(text: String, font: PdfFont, size: Float): Float
}

/** Страница в пунктах (A4 по умолчанию) и поля. [footer] -- полоса под нумерацию внутри нижнего поля. */
data class PdfGeometry(
    val width: Float = 595f,
    val height: Float = 842f,
    val margin: Float = 48f,
    val footer: Float = 26f,
) {
    val left: Float get() = margin
    val right: Float get() = width - margin
    val top: Float get() = margin
    /** Нижняя граница содержимого (выше футера). */
    val bottom: Float get() = height - margin - footer
    val contentWidth: Float get() = right - left
    val contentHeight: Float get() = bottom - top
}

data class PdfSpeaker(val id: String, val name: String)

/** Реплика для печати: время начала, говорящий и текст. */
data class PdfUtterance(val speakerId: String, val startMs: Long, val text: String)

/**
 * Всё, что попадает в PDF. [protocol] -- текст протокола LLM, если он уже составлен (иначе `null`/пусто и раздела нет).
 */
data class PdfDocModel(
    val title: String,
    val dateText: String,
    val durationMs: Long,
    val speakers: List<PdfSpeaker>,
    val utterances: List<PdfUtterance>,
    val protocol: String? = null,
    val footerNote: String = "Нейро-стенографист · обработано на устройстве",
)

/** Примитив рисования; координаты в пунктах от левого верхнего угла страницы, [Text.baseline] -- линия шрифта. */
sealed interface PdfOp {
    data class Text(val x: Float, val baseline: Float, val text: String, val font: PdfFont, val size: Float) : PdfOp

    /** Рамка с жёсткой тенью; [accentBar] -- акцентная полоса вдоль верха рамки (единственное место акцента). */
    data class Box(val x: Float, val y: Float, val w: Float, val h: Float, val border: Float, val accentBar: Boolean = false) : PdfOp

    /** Квадратик цвета говорящего (цвет подбирает рендерер по [speakerId]). */
    data class Swatch(val x: Float, val y: Float, val size: Float, val speakerId: String) : PdfOp

    data class Rule(val x1: Float, val y: Float, val x2: Float, val thickness: Float) : PdfOp
}

class PdfPage(val ops: List<PdfOp>)
