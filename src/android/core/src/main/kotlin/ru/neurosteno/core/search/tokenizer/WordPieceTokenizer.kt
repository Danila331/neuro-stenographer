package ru.neurosteno.core.search.tokenizer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * WordPiece (rubert-tiny-turbo): `BertNormalizer` (clean_text, handle_chinese_chars; без lowercase и strip_accents), `BertPreTokenizer`
 * (пробелы и знаки препинания отдельными токенами), жадное самое длинное совпадение с префиксом `##`; слово без разбиения -> `[UNK]`.
 */
internal class WordPieceTokenizer(root: JsonObject) : TextTokenizer {
    private val model = root["model"]!!.jsonObject
    private val vocab: Map<String, Int> = model["vocab"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }
    private val unkId = vocab.getValue(model["unk_token"]?.jsonPrimitive?.content ?: "[UNK]")
    private val prefix = model["continuing_subword_prefix"]?.jsonPrimitive?.content ?: "##"
    private val maxChars = model["max_input_chars_per_word"]?.jsonPrimitive?.int ?: 100
    private val added = AddedVocab(root["added_tokens"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList()))
    private val cls: Int
    private val sep: Int
    override val padId: Int = padIdOf(root)

    private val cleanText: Boolean
    private val chinese: Boolean
    private val lowercase: Boolean
    private val stripAccents: Boolean

    init {
        val (c, s) = templateIds(root)
        cls = c
        sep = s
        val n = root["normalizer"].obj()
        require(n == null || n["type"]?.jsonPrimitive?.content == "BertNormalizer") { "ожидается BertNormalizer" }
        cleanText = n?.get("clean_text")?.jsonPrimitive?.boolean ?: true
        chinese = n?.get("handle_chinese_chars")?.jsonPrimitive?.boolean ?: true
        lowercase = n?.get("lowercase")?.jsonPrimitive?.boolean ?: true
        stripAccents = n?.get("strip_accents")?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.boolean } ?: lowercase
        require(!lowercase && !stripAccents) { "lowercase/strip_accents не реализованы (в rubert-tiny-turbo выключены)" }
    }

    private fun isControl(cp: Int): Boolean {
        if (isRustWhitespace(cp)) return false
        return when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED -> true
            else -> false
        }
    }

    private fun isChinese(cp: Int) = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B73F ||
        cp in 0x2B740..0x2B81F || cp in 0x2B920..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F

    private fun isPunct(cp: Int): Boolean {
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length + 8)
        var i = 0
        // clean_text: убрать \0, U+FFFD и управляющие, пробельные -> ' '
        val cleaned = StringBuilder(s.length)
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cleanText) {
                if (cp == 0 || cp == 0xFFFD || isControl(cp)) continue
                cleaned.appendCodePoint(if (isRustWhitespace(cp)) ' '.code else cp)
            } else cleaned.appendCodePoint(cp)
        }
        i = 0
        while (i < cleaned.length) {
            val cp = cleaned.codePointAt(i)
            i += Character.charCount(cp)
            if (chinese && isChinese(cp)) sb.append(' ').appendCodePoint(cp).append(' ') else sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun preTokenize(s: String): List<String> {
        val words = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        fun flush() { if (cur.isNotEmpty()) { words += cur.toString(); cur.setLength(0) } }
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isRustWhitespace(cp) -> flush()
                isPunct(cp) -> { flush(); words += String(Character.toChars(cp)) }
                else -> cur.appendCodePoint(cp)
            }
        }
        flush()
        return words
    }

    private fun wordPiece(word: String, out: MutableList<Int>) {
        val cps = word.codePoints().toArray()
        if (cps.size > maxChars) { out += unkId; return }
        val pieces = ArrayList<Int>()
        var start = 0
        while (start < cps.size) {
            var end = cps.size
            var found = -1
            while (start < end) {
                var sub = String(cps, start, end - start)
                if (start > 0) sub = prefix + sub
                val id = vocab[sub]
                if (id != null) { found = id; break }
                end--
            }
            if (found < 0) { out += unkId; return }
            pieces += found
            start = end
        }
        out += pieces
    }

    override fun encode(text: String, maxLen: Int): IntArray {
        val ids = ArrayList<Int>()
        for (p in added.extract(text, ::normalize)) {
            if (p.id != null) ids += p.id else for (w in preTokenize(p.text)) wordPiece(w, ids)
        }
        val keep = if (maxLen > 0) minOf(ids.size, maxOf(0, maxLen - 2)) else ids.size
        val out = IntArray(keep + 2)
        out[0] = cls
        for (i in 0 until keep) out[1 + i] = ids[i]
        out[keep + 1] = sep
        return out
    }
}
