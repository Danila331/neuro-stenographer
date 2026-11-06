package ru.neurosteno.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LanguageSmootherTest {
    private fun s(vararg p: String?, d: Long = 3000) = LanguageSmoother.smooth(p.toList(), p.map { d })

    @Test
    fun `stable languages pass through`() {
        assertEquals(listOf("ru", "ru", "ru"), s("ru", "ru", "ru"))
        assertEquals(listOf("ru", "en", "en", "ru", "ru"), s("ru", "en", "en", "ru", "ru"))
    }

    @Test
    fun `single short outlier chunk is ignored in both directions`() {
        assertEquals(listOf("ru", "ru", "ru"), s("ru", "en", "ru"))
        assertEquals(listOf("ru", "en", "en", "en"), s("ru", "en", "en", "en"))
        assertEquals(listOf("ru", "en", "en", "en", "ru", "ru"), s("ru", "en", "en", "en", "ru", "ru"))
        assertEquals(listOf("en", "en", "en", "ru", "ru"), s("en", "en", "en", "ru", "ru")) // начальное ru сразу меняется: следующий подтверждает
        assertEquals(listOf("ru", "en", "en", "en"), s("ru", "en", "en", "en"))
        assertEquals(listOf("ru", "ru", "ru", "ru"), s("ru", "en", "ru", "ru")) // выброс
    }

    @Test
    fun `long confident chunk switches immediately`() {
        assertEquals(listOf("ru", "en", "en"), LanguageSmoother.smooth(listOf("ru", "en", "ru"), listOf(3000, 8000, 3000))) // хвостовой короткий ru без подтверждения не переключает
        assertEquals(listOf("ru", "en", "ru"), LanguageSmoother.smooth(listOf("ru", "en", "ru"), listOf(3000, 8000, 7000)))
        assertEquals(listOf("en"), LanguageSmoother.smooth(listOf("en"), listOf(9000)))
    }

    @Test
    fun `unknown chunks inherit the current state`() {
        // en, ?, en: на чанке 0 следующий известный (en) подтверждает; ? наследует en
        assertEquals(listOf("en", "en", "en", "en"), s("en", null, "en", "ru")) // хвостовой короткий "ru" без подтверждения не переключает
        assertEquals(listOf("ru", "ru"), s(null, null))
    }

    @Test
    fun `language codes other than en are russian and empty input is fine`() {
        assertEquals(listOf("ru", "ru"), s("zh", "uk"))
        assertEquals(emptyList<String>(), LanguageSmoother.smooth(emptyList(), emptyList()))
        assertEquals(listOf("ru"), s("en")) // единственный короткий чанк без подтверждения
    }
}
