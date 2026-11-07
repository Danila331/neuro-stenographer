package ru.neurosteno.app.edit

import ru.neurosteno.core.Utterance

/** Сравнение старых и повторно распознанных реплик для UX «Без изменений». */
object FragmentChangeDetector {
    fun isUnchanged(
        old: List<Utterance>,
        updated: List<Utterance>,
        speakerRemap: Map<String, String> = emptyMap(),
        boundaryToleranceMs: Long = DEFAULT_BOUNDARY_TOLERANCE_MS,
    ): Boolean {
        if (old.size != updated.size || boundaryToleranceMs < 0) return false

        val before = old.sortedWith(compareBy<Utterance> { it.startMs }.thenBy { it.endMs })
        val after = updated.sortedWith(compareBy<Utterance> { it.startMs }.thenBy { it.endMs })
        return before.zip(after).all { (was, now) ->
            was.text == now.text &&
                speakerRemap[was.speakerId].orElse(was.speakerId) == now.speakerId &&
                within(was.startMs, now.startMs, boundaryToleranceMs) &&
                within(was.endMs, now.endMs, boundaryToleranceMs)
        }
    }

    private fun within(a: Long, b: Long, tolerance: Long): Boolean =
        (maxOf(a, b) - minOf(a, b)) <= tolerance

    private fun String?.orElse(fallback: String): String = this ?: fallback

    const val DEFAULT_BOUNDARY_TOLERANCE_MS = 50L
}
