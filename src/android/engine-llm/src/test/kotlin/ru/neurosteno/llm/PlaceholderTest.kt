package ru.neurosteno.llm

import org.junit.Assert.assertTrue
import org.junit.Test

/** Нативную часть на JVM-юнитах не загрузить; проверка с реальной моделью -- tools/jvm-smoke (scripts/jvm_smoke.sh). */
class PlaceholderTest {
    @Test
    fun callbackInterfaceIsSam() {
        val cb = TokenCallback { it.isNotEmpty() }
        assertTrue(cb.onToken(byteArrayOf(1)))
    }
}
