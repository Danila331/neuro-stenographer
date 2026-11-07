package ru.neurosteno.app.insight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.neurosteno.core.summary.SummaryDecision
import ru.neurosteno.core.summary.SummaryDoc
import ru.neurosteno.core.summary.SummaryPoint
import ru.neurosteno.core.summary.SummaryRef
import ru.neurosteno.core.summary.SummaryTask
import ru.neurosteno.core.summary.SummaryTopic

class AnswerPolishTest {
    @Test
    fun repeatedSentenceAfterCitationIsRemovedAndCitationKept() {
        val raw = "Дата сегодня — 15 ноября. [00:24, Говорящий 1] Дата сегодня — 15 ноября."
        assertEquals("Дата сегодня — 15 ноября. [00:24, Говорящий 1]", AnswerPolish.dedupe(raw))
    }

    @Test
    fun citationBeforeTerminatorStyleIsPreserved() {
        val raw = "Мурзик сказал про мышей на складе [00:04, Говорящий 2]. Мурзик сказал про мышей на складе [00:04, Говорящий 2]."
        assertEquals("Мурзик сказал про мышей на складе [00:04, Говорящий 2].", AnswerPolish.dedupe(raw))
    }

    @Test
    fun comparisonIgnoresCaseSpacesPunctuationAndYo() {
        val raw = "Решили купить синюю МИСКУ с рыбками!  решили купить синюю миску, с рыбками. Ещё решили, всё это оплатить."
        assertEquals("Решили купить синюю МИСКУ с рыбками! Ещё решили, всё это оплатить.", AnswerPolish.dedupe(raw))
        assertEquals("Всё ясно из этого ответа. Остальное по плану.", AnswerPolish.dedupe("Всё ясно из этого ответа. Все ясно из этого ответа! Остальное по плану."))
    }

    @Test
    fun citationsOfTheRemovedCopyMoveToTheSurvivorWithoutDuplicates() {
        val raw = "Багира назначена дежурной на складе. [00:36, Говорящий 1] Багира назначена дежурной на складе. [00:40, Говорящий 2] [00:36, Говорящий 1]"
        assertEquals("Багира назначена дежурной на складе. [00:36, Говорящий 1] [00:40, Говорящий 2]", AnswerPolish.dedupe(raw))
    }

    @Test
    fun shortAnswersAndMeaningfulRepeatsAreUntouched() {
        assertEquals("Да. Да. Нет.", AnswerPolish.dedupe("Да. Да. Нет."))
        assertEquals("Очень, очень важно. Очень, очень важно.", AnswerPolish.dedupe("Очень, очень важно. Очень, очень важно."), )
        val three = "Это не так. [00:01, Говорящий 1] Это не так. [00:02, Говорящий 1]"
        assertEquals(three, AnswerPolish.dedupe(three)) // 3 слова < порога
    }

    @Test
    fun textWithoutRepeatsDecimalsAndDatesIsReturnedAsIs() {
        val t = "Версия 1.5 вышла 15.11.2026 в 10:00. Следующая версия выйдет позже обычного срока. [00:24, Говорящий 1]"
        assertEquals(t, AnswerPolish.dedupe(t))
        assertEquals("", AnswerPolish.dedupe(""))
        assertEquals("Без точки в конце и без повторов", AnswerPolish.dedupe("Без точки в конце и без повторов"))
    }

    @Test
    fun partialStreamingTextWithTrailingRepeatIsTrimmedToo() {
        assertEquals("Дата сегодня — 15 ноября.", AnswerPolish.dedupe("Дата сегодня — 15 ноября. Дата сегодня — 15 ноября."))
    }

    @Test
    fun summaryRepeatsAreCollapsedWithMergedRefs() {
        fun r(n: Int, t: Long, s: String = "speaker_1") = SummaryRef(n, t, s)
        val doc = SummaryDoc(
            mode = "llm", overview = "Обсудили порядок вопросов повестки. Обсудили порядок вопросов повестки. Решили начать с мышей.",
            topics = listOf(
                SummaryTopic(
                    "Мыши", 0, 60_000,
                    points = listOf(
                        SummaryPoint("speaker_1", "Нужен дежурный на складе", listOf(r(1, 1_000))),
                        SummaryPoint("speaker_2", "Нужен дежурный на складе", listOf(r(2, 2_000, "speaker_2"))), // другой говорящий -- не повтор
                        SummaryPoint("speaker_1", "Нужен  дежурный на складе!", listOf(r(3, 3_000))),
                        SummaryPoint("speaker_1", "Да", listOf(r(4, 4_000))),
                        SummaryPoint("speaker_1", "Да", listOf(r(5, 5_000))), // короткие не трогаем
                    ),
                    decisions = listOf(SummaryDecision("Назначить Багиру дежурной", listOf(r(6, 6_000))), SummaryDecision("назначить багиру дежурной.", listOf(r(7, 7_000)))),
                    tasks = listOf(SummaryTask("Говорящий 2", "составить отчёт к утру", listOf(r(8, 8_000))), SummaryTask("Говорящий 2", "Составить отчёт к утру", listOf(r(9, 9_000))), SummaryTask("Говорящий 1", "составить отчёт к утру", listOf(r(10, 10_000)))),
                ),
            ),
        )
        val c = SummaryPolish.clean(doc)
        assertEquals("Обсудили порядок вопросов повестки. Решили начать с мышей.", c.overview)
        val t = c.topics.single()
        assertEquals(listOf("Нужен дежурный на складе", "Нужен дежурный на складе", "Да", "Да"), t.points.map { it.text.replace("!", "").replace("  ", " ") })
        assertEquals(listOf(1, 3), t.points[0].refs.map { it.n }) // ссылки повтора объединены
        assertEquals(1, t.decisions.size)
        assertEquals(listOf(6, 7), t.decisions[0].refs.map { it.n })
        assertEquals(2, t.tasks.size) // одинаковый текст у разных исполнителей -- разные задачи
        assertEquals(listOf(8, 9), t.tasks[0].refs.map { it.n })
        assertTrue(SummaryPolish.clean(c) == c) // идемпотентно
    }
}
