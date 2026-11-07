package ru.neurosteno.app.edit

import ru.neurosteno.app.data.StoredTranscript
import ru.neurosteno.core.TimeSpan
import ru.neurosteno.core.fragment.FragmentResult

/** Расшифровка после пересчёта фрагмента и подсветка добавленных реплик. */
class FragmentApplied(val stored: StoredTranscript, val addedSpans: List<TimeSpan>)

object FragmentApplier {
    /**
     * Переносит результат [FragmentResult] в сохранённую расшифровку: новые реплики берутся из `r.transcript`, а пользовательские
     * имена говорящих переезжают по `speakerRemap` (старый `speaker_N` -> итоговый после перенумерации по ТЗ). Говорящий, которого
     * в результате не осталось, теряет имя. Правка помечается как сделанная пользователем (слова других реплик не затронуты).
     */
    fun apply(old: StoredTranscript, r: FragmentResult): FragmentApplied {
        val names = LinkedHashMap<String, String>()
        old.speakerNames.forEach { (oldId, name) -> r.speakerRemap[oldId]?.let { names.putIfAbsent(it, name) } }
        val stored = StoredTranscript.of(r.transcript, names, edited = true)
        return FragmentApplied(stored, r.added.map { TimeSpan(it.startMs, it.endMs) })
    }
}
