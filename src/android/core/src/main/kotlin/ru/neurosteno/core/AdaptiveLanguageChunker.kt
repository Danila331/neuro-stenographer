package ru.neurosteno.core

/** LID on short subsegments; original boundaries are preserved when a chunk has one language. */
object AdaptiveLanguageChunker {
    fun split(
        chunks: List<TimeSpan>,
        detect: (TimeSpan) -> String?,
        maxS: Double = 5.0,
    ): Pair<List<TimeSpan>, List<String?>> {
        val maxMs = (maxS * 1000).toLong()
        val out = ArrayList<TimeSpan>()
        val raw = ArrayList<String?>()
        for (chunk in chunks) {
            val pieces = ArrayList<TimeSpan>()
            var start = chunk.startMs
            while (start < chunk.endMs) {
                val end = minOf(chunk.endMs, start + maxMs)
                pieces += TimeSpan(start, end)
                start = end
            }
            val predictions = pieces.map(detect)
            val languages = predictions.filterNotNull().toSet()
            if (pieces.size == 1 || languages.size <= 1) {
                out += chunk
                raw += predictions.firstOrNull { it != null }
            } else {
                out += pieces
                raw += predictions
            }
        }
        return out to raw
    }

    /** Smooth VAD-sized LID chunks first, then preserve original ASR chunks whose labels agree. */
    fun plan(
        chunks: List<TimeSpan>,
        lidChunks: List<TimeSpan>,
        detect: (TimeSpan) -> String?,
    ): Triple<List<TimeSpan>, List<String?>, List<String>> {
        val groups = ArrayList<List<Int>>()
        var cursor = 0
        for (chunk in chunks) {
            val group = ArrayList<Int>()
            while (cursor < lidChunks.size && lidChunks[cursor].endMs <= chunk.endMs) {
                if (lidChunks[cursor].startMs < chunk.startMs) break
                group += cursor
                cursor++
            }
            groups += group
        }
        if (groups.any { it.isEmpty() } || cursor != lidChunks.size) {
            val raw = chunks.map(detect)
            return Triple(chunks, raw, LanguageSmoother.smooth(raw, chunks.map { it.endMs - it.startMs }))
        }

        val rawFine = lidChunks.map(detect)
        val fineLanguages = LanguageSmoother.smooth(rawFine, lidChunks.map { it.endMs - it.startMs })
        val out = ArrayList<TimeSpan>()
        val raw = ArrayList<String?>()
        val languages = ArrayList<String>()
        for ((chunk, group) in chunks.zip(groups)) {
            val labels = group.map(fineLanguages::get)
            if (labels.toSet().size == 1) {
                out += chunk
                raw += group.firstNotNullOfOrNull { rawFine[it] }
                languages += labels.first()
            } else {
                out += group.map(lidChunks::get)
                raw += group.map(rawFine::get)
                languages += labels
            }
        }
        return Triple(out, raw, languages)
    }
}
