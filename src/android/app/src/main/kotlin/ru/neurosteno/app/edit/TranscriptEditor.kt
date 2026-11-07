package ru.neurosteno.app.edit

import ru.neurosteno.core.Transcript
import ru.neurosteno.core.TranscriptJson
import ru.neurosteno.core.Utterance
import ru.neurosteno.core.Word
import ru.neurosteno.core.speakerLabel

/**
 * Редактируемая расшифровка: реплики + имена говорящих. Инвариант: после каждой операции [TranscriptEditor.canonical]
 * приводит данные к виду, который выдал бы экспорт (сортировка, перенумерация `speaker_1..N`, имена переезжают вместе
 * с идентификаторами) -- поэтому экспортёры из `:core` можно вызывать на этих данных напрямую.
 */
data class EditableTranscript(
    val utterances: List<Utterance>,
    val speakerNames: Map<String, String>,
    val durationMs: Long,
) {
    /** Идентификаторы говорящих в порядке первого появления. */
    val speakerIds: List<String> get() = utterances.map { it.speakerId }.distinct()

    fun displayName(speakerId: String): String = speakerNames[speakerId]?.takeIf { it.isNotBlank() } ?: defaultName(speakerId)

    companion object {
        /** `speaker_3` -> «Говорящий 3». */
        fun defaultName(speakerId: String): String =
            speakerId.removePrefix("speaker_").toIntOrNull()?.let { "Говорящий $it" } ?: speakerId

        fun of(transcript: Transcript, names: Map<String, String> = emptyMap()) =
            TranscriptEditor.canonical(EditableTranscript(transcript.utterances, names, transcript.meta.audioDurationMs))
    }
}

object TranscriptEditor {

    fun setText(t: EditableTranscript, index: Int, text: String): EditableTranscript {
        val u = t.utterances.getOrNull(index) ?: return t
        if (text.trim() == u.text) return t
        // слова больше не соответствуют тексту -- сбрасываем (в расширенном экспорте у такой реплики words пуст)
        return canonical(replace(t, index, u.copy(text = text, words = emptyList())))
    }

    fun setSpeaker(t: EditableTranscript, index: Int, speakerId: String): EditableTranscript {
        val u = t.utterances.getOrNull(index) ?: return t
        if (u.speakerId == speakerId) return t
        return canonical(replace(t, index, u.copy(speakerId = speakerId)))
    }

    /** Объединяет реплику [index] со следующей; говорящий -- первой, время -- от начала первой до конца последней. */
    fun mergeWithNext(t: EditableTranscript, index: Int): EditableTranscript {
        val a = t.utterances.getOrNull(index) ?: return t
        val b = t.utterances.getOrNull(index + 1) ?: return t
        val merged = a.copy(
            startMs = minOf(a.startMs, b.startMs),
            endMs = maxOf(a.endMs, b.endMs),
            text = (a.text.trim() + " " + b.text.trim()).trim(),
            words = a.words + b.words,
        )
        val list = t.utterances.toMutableList()
        list[index] = merged
        list.removeAt(index + 1)
        return canonical(t.copy(utterances = list))
    }

    fun delete(t: EditableTranscript, index: Int): EditableTranscript {
        if (index !in t.utterances.indices) return t
        return canonical(t.copy(utterances = t.utterances.filterIndexed { i, _ -> i != index }))
    }

    /** Пустое имя возвращает стандартное «Говорящий N». */
    fun rename(t: EditableTranscript, speakerId: String, name: String): EditableTranscript {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        val names = t.speakerNames.toMutableMap()
        if (clean.isEmpty() || clean == EditableTranscript.defaultName(speakerId)) names.remove(speakerId) else names[speakerId] = clean
        return t.copy(speakerNames = names)
    }

    /** Свободный идентификатор для нового говорящего. */
    fun newSpeakerId(t: EditableTranscript): String {
        val max = t.speakerIds.mapNotNull { it.removePrefix("speaker_").toIntOrNull() }.maxOrNull() ?: 0
        return speakerLabel(max + 1)
    }

    /**
     * Сортировка, очистка текста, перенумерация говорящих по первому появлению -- ровно как в [TranscriptJson.normalize];
     * имена переносятся на новые идентификаторы. Идемпотентна.
     */
    fun canonical(t: EditableTranscript): EditableTranscript {
        // нормализатор из :core пересоздаёт реплики, поэтому индекс исходной реплики прячем в words и по нему возвращаем слова
        val tagged = t.utterances.mapIndexed { i, u -> u.copy(words = listOf(Word(i.toString(), 0, 0))) }
        val norm = TranscriptJson.normalize(tagged, t.durationMs)
        val idMap = LinkedHashMap<String, String>()
        val out = norm.map { n ->
            val original = t.utterances[n.words.first().text.toInt()]
            idMap.putIfAbsent(original.speakerId, n.speakerId)
            n.copy(words = original.words)
        }
        val names = LinkedHashMap<String, String>()
        t.speakerNames.forEach { (old, name) -> idMap[old]?.let { names[it] = name } }
        return EditableTranscript(out, names, t.durationMs)
    }

    /** Индекс реплики, звучащей в момент [positionMs] (последняя начавшаяся и ещё не закончившаяся); -1 -- тишина. */
    fun indexAt(utterances: List<Utterance>, positionMs: Long): Int {
        var lo = 0
        var hi = utterances.size - 1
        var found = -1
        while (lo <= hi) { // последняя реплика с start <= position (список отсортирован по start)
            val mid = (lo + hi) ushr 1
            if (utterances[mid].startMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        // при пересечениях ищем назад ближайшую, которая ещё не закончилась
        var i = found
        var steps = 0
        while (i >= 0 && steps < 8) {
            if (positionMs < utterances[i].endMs) return i
            i--; steps++
        }
        return -1
    }

    private fun replace(t: EditableTranscript, index: Int, u: Utterance): EditableTranscript =
        t.copy(utterances = t.utterances.toMutableList().also { it[index] = u })
}
