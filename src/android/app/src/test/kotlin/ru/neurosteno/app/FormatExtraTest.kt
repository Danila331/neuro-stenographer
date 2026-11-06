package ru.neurosteno.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.neurosteno.app.ui.Format
import ru.neurosteno.core.Stage

class FormatExtraTest {
    @Test
    fun durationReadable() {
        assertEquals("45 с", Format.duration(45_000))
        assertEquals("12 мин 05 с", Format.duration(725_000))
        assertEquals("1 ч 02 мин", Format.duration(3_720_000))
    }

    @Test
    fun pluralRussian() {
        assertEquals("1 реплика", Format.plural(1, "реплика", "реплики", "реплик"))
        assertEquals("2 реплики", Format.plural(2, "реплика", "реплики", "реплик"))
        assertEquals("5 реплик", Format.plural(5, "реплика", "реплики", "реплик"))
        assertEquals("11 реплик", Format.plural(11, "реплика", "реплики", "реплик"))
        assertEquals("21 реплика", Format.plural(21, "реплика", "реплики", "реплик"))
        assertEquals("112 реплик", Format.plural(112, "реплика", "реплики", "реплик"))
    }

    @Test
    fun bytesInRussianUnits() {
        assertEquals("1,6 ГБ", Format.bytes(1_717_986_918))
        assertEquals("224 МБ", Format.bytes(224L * 1024 * 1024))
        assertEquals("12 КБ", Format.bytes(12 * 1024))
    }

    @Test
    fun stageTitlesMatchSpec() {
        assertEquals("Декодирование", Format.stageTitle(Stage.DECODE))
        assertEquals("Поиск речи", Format.stageTitle(Stage.VAD))
        assertEquals("Определение языка", Format.stageTitle(Stage.LID))
        assertEquals("Распознавание", Format.stageTitle(Stage.ASR))
        assertEquals("Разделение говорящих", Format.stageTitle(Stage.DIARIZATION))
        assertEquals("Сборка реплик", Format.stageTitle(Stage.ALIGN))
        assertEquals("Пунктуация", Format.stageTitle(Stage.PUNCTUATION))
    }
}
