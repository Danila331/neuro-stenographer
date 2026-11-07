package ru.neurosteno.core.live

/** Файлы потоковой transducer-модели (encoder/decoder/joiner/tokens) по списку имён каталога. */
data class StreamingModelFiles(val encoder: String, val decoder: String, val joiner: String, val tokens: String) {
    companion object {
        /**
         * Выбирает файлы по именам: `encoder*.onnx`, `decoder*.onnx`, `joiner*.onnx`, `tokens.txt`. Если есть и int8-, и fp32-вариант,
         * берётся int8 (квантованный encoder/joiner в 3-4 раза меньше и быстрее; у zipformer-vosk decoder только fp32).
         * Бросает [IllegalArgumentException] с перечнем недостающего.
         */
        fun resolve(names: List<String>): StreamingModelFiles {
            fun pick(prefix: String): String? {
                val c = names.filter { it.startsWith(prefix) && it.endsWith(".onnx") }.sorted()
                return c.firstOrNull { it.contains("int8") } ?: c.firstOrNull()
            }
            val enc = pick("encoder")
            val dec = pick("decoder")
            val join = pick("joiner")
            val tok = names.firstOrNull { it == "tokens.txt" }
            val missing = listOfNotNull(
                "encoder*.onnx".takeIf { enc == null }, "decoder*.onnx".takeIf { dec == null },
                "joiner*.onnx".takeIf { join == null }, "tokens.txt".takeIf { tok == null },
            )
            require(missing.isEmpty()) { "В каталоге потоковой модели нет файлов: ${missing.joinToString()}" }
            return StreamingModelFiles(enc!!, dec!!, join!!, tok!!)
        }
    }
}
