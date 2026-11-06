package ru.neurosteno.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.neurosteno.app.ui.Format

class FormatTest {
    @Test
    fun clockFormatsMinutesAndHours() {
        assertEquals("00:01", Format.clock(1250))
        assertEquals("1:02:03", Format.clock(3_723_004))
        assertEquals("00:00", Format.clock(-5))
    }

    @Test
    fun exportNameReplacesExtension() {
        assertEquals("meeting.json", Format.exportFileName("meeting.m4a"))
        assertEquals("audio.v2.json", Format.exportFileName("audio.v2.wav"))
        assertEquals("rec.json", Format.exportFileName("rec"))
    }
}
