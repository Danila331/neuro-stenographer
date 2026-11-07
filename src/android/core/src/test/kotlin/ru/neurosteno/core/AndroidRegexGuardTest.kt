package ru.neurosteno.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Регулярки Android (ICU) не знают флаг `(?U)` и `Pattern.UNICODE_CHARACTER_CLASS`: на JVM они работают, на устройстве бросают
 * PatternSyntaxException при инициализации (так «Числа: словами» ронял обработку на эмуляторе). Тесты JVM этого не видят,
 * поэтому запрещаем такие конструкции статически во всех модулях обработки.
 */
class AndroidRegexGuardTest {
    private val modules = listOf("core", "audio", "engine-sherpa", "engine-llm", "engine-embed", "store-objectbox", "app")
    private val inlineUnicodeFlag = Regex("""\(\?[a-zA-Z]*U[a-zA-Z]*[):]""")

    @Test
    fun `no Unicode-class regex flags in production code`() {
        val bad = ArrayList<String>()
        for (m in modules) {
            val dir = File(TestPaths.androidRoot, "$m/src/main")
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    val code = line.substringBefore("//")
                    if (inlineUnicodeFlag.containsMatchIn(code) || "UNICODE_CHARACTER_CLASS" in code) {
                        bad += "${f.relativeTo(TestPaths.androidRoot)}:${i + 1}"
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), bad)
    }
}
