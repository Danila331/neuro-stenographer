package ru.neurosteno.llm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * B58 (P1): строки уходят в llama.cpp стандартным UTF-8. `GetStringUTFChars` отдавал «модифицированный UTF-8»: emoji (вне BMP) -- две
 * трёхбайтовые суррогатные последовательности вместо одной четырёхбайтовой, U+0000 -- C0 80. Токенизатор такое не понимает.
 * Нужна хостовая JNI (`scripts/build_host_jni.sh`, `LLM_HOST_BUILD=<каталог>`); нет -- тесты пропускаются.
 */
class Utf8JniTest {
    @Before
    fun requireNative() {
        val ok = try { LlamaNative.systemInfo(); true } catch (e: UnsatisfiedLinkError) { false } catch (e: NoClassDefFoundError) { false }
        assumeTrue("нет хостовой libneurosteno_llm (LLM_HOST_BUILD)", ok)
    }

    private val emoji = "😀" // U+1F600: в UTF-8 F0 9F 98 80

    @Test
    fun oldBehaviourBrokeEmoji_reproduction() {
        val legacy = LlamaNative.legacyUtf8Bytes("a${emoji}b")!!
        // modified UTF-8: суррогаты ED A0 BD / ED B8 80 -- 6 байт вместо 4, это не валидный UTF-8
        assertEquals("ED A0 BD ED B8 80", legacy.copyOfRange(1, 7).joinToString(" ") { "%02X".format(it) })
        assertTrue(String(legacy, Charsets.UTF_8) != "a${emoji}b")
    }

    @Test
    fun emojiIsStandardFourByteUtf8() {
        assertArrayEquals("a${emoji}b".toByteArray(Charsets.UTF_8), LlamaNative.utf8Bytes("a${emoji}b"))
        assertEquals("F0 9F 98 80", LlamaNative.utf8Bytes(emoji)!!.joinToString(" ") { "%02X".format(it) })
    }

    @Test
    fun cyrillicAndMixedTextMatchTheJvmEncoder() {
        val s = "Привет, мир! ёЁ — «цитата» 日本語 $emoji 👨‍👩‍👧 end"
        assertArrayEquals(s.toByteArray(Charsets.UTF_8), LlamaNative.utf8Bytes(s))
    }

    @Test
    fun nulCharacterIsASingleZeroByteNotC080() {
        val b = LlamaNative.utf8Bytes("a\u0000b")!!
        assertArrayEquals(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()), b)
    }

    @Test
    fun loneSurrogatesBecomeReplacementCharacter() {
        val b = LlamaNative.utf8Bytes("x\uD83Dy\uDE00z")!! // одинокие суррогаты
        assertEquals("x�y�z", String(b, Charsets.UTF_8))
    }

    @Test
    fun emptyStringAndLongText() {
        assertEquals(0, LlamaNative.utf8Bytes("")!!.size)
        val long = (emoji + "Слово ").repeat(50_000)
        assertArrayEquals(long.toByteArray(Charsets.UTF_8), LlamaNative.utf8Bytes(long))
    }

    @Test
    fun roundTripKeepsText() {
        val s = "путь/к/модели-$emoji/файл.gguf"
        assertEquals(s, LlamaNative.utf8RoundTrip(s))
        assertNotNull(LlamaNative.utf8RoundTrip(""))
    }
}
