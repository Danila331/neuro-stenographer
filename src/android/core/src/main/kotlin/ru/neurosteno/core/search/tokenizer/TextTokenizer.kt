package ru.neurosteno.core.search.tokenizer

import java.io.InputStream
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Токенайзер эмбеддера: текст -> id токенов (с [CLS]/[SEP], усечением до [maxLen]). Портирует то, что нужно двум моделям поиска из
 * `tokenizer.json` библиотеки Hugging Face `tokenizers`: WordPiece (rubert-tiny-turbo, `BertNormalizer` без lowercase) и byte-level BPE
 * (USER2-small, ModernBERT, NFC). Результат совпадает с Python `tokenizers` бит в бит (`TokenizerParityTest`).
 */
interface TextTokenizer {
    /** id токенов `[CLS] текст [SEP]`, не длиннее [maxLen] (усечение справа, как `enable_truncation`). */
    fun encode(text: String, maxLen: Int): IntArray

    /** id для заполнения батча (`[PAD]`). */
    val padId: Int

    companion object {
        /** Читает `tokenizer.json` (тип модели -- по полю `model.type`: `WordPiece` | `BPE`). */
        fun load(input: InputStream): TextTokenizer {
            val root = Json.parseToJsonElement(input.bufferedReader(Charsets.UTF_8).readText()).jsonObject
            return when (val type = root["model"]!!.jsonObject["type"]!!.jsonPrimitive.content) {
                "WordPiece" -> WordPieceTokenizer(root)
                "BPE" -> BpeTokenizer(root)
                else -> throw IllegalArgumentException("Токенайзер типа $type не поддерживается")
            }
        }
    }
}

/** Добавленные токены (`added_tokens`): ищутся в тексте до нормализации и модели (как `AddedVocabulary` в Hugging Face tokenizers). */
internal class AddedVocab(arr: JsonArray) {
    class Tok(val content: String, val id: Int, val normalized: Boolean, val lstrip: Boolean, val rstrip: Boolean, val singleWord: Boolean)

    val tokens: List<Tok> = arr.map { e ->
        val o = e.jsonObject
        Tok(
            o["content"]!!.jsonPrimitive.content, o["id"]!!.jsonPrimitive.int,
            o["normalized"]?.jsonPrimitive?.boolean ?: false, o["lstrip"]?.jsonPrimitive?.boolean ?: false,
            o["rstrip"]?.jsonPrimitive?.boolean ?: false, o["single_word"]?.jsonPrimitive?.boolean ?: false,
        )
    }
    private val rawToks = tokens.filter { !it.normalized }
    private val normToks = tokens.filter { it.normalized }

    /** Кусок текста: либо добавленный токен ([id] != null), либо обычный текст. */
    class Piece(val text: String, val id: Int?)

    private fun split(text: String, toks: List<Tok>): List<Piece> {
        if (toks.isEmpty() || text.isEmpty()) return listOf(Piece(text, null))
        val out = ArrayList<Piece>()
        var last = 0
        var i = 0
        while (i < text.length) {
            // самый левый старт, на нём самое длинное совпадение (LeftmostLongest)
            var best: Tok? = null
            for (t in toks) if (text.startsWith(t.content, i) && (best == null || t.content.length > best.content.length)) best = t
            if (best == null) { i++; continue }
            var start = i
            var end = i + best.content.length
            if (best.singleWord) {
                val before = start > 0 && isWordChar(text.codePointBefore(start))
                val after = end < text.length && isWordChar(text.codePointAt(end))
                if (before || after) { i++; continue }
            }
            if (best.lstrip) while (start > last && isRustWhitespace(text.codePointBefore(start))) start -= Character.charCount(text.codePointBefore(start))
            if (best.rstrip) while (end < text.length && isRustWhitespace(text.codePointAt(end))) end += Character.charCount(text.codePointAt(end))
            if (start > last) out += Piece(text.substring(last, start), null)
            out += Piece(best.content, best.id)
            last = end
            i = end
        }
        if (last < text.length) out += Piece(text.substring(last), null)
        return out
    }

    /** Сырые токены -> нормализация остального -> нормализованные токены (порядок `extract_and_normalize`). */
    fun extract(text: String, normalize: (String) -> String): List<Piece> {
        val out = ArrayList<Piece>()
        for (p in split(text, rawToks)) {
            if (p.id != null) { out += p; continue }
            val n = normalize(p.text)
            out += split(n, normToks)
        }
        return out.filter { it.id != null || it.text.isNotEmpty() }
    }

    private fun isWordChar(cp: Int) = Character.isLetterOrDigit(cp) || cp == '_'.code
}

/** Юникод White_Space (как `char::is_whitespace` в Rust и `\s` в regex Rust). */
internal fun isRustWhitespace(cp: Int): Boolean = when (cp) {
    in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
    else -> false
}

internal fun isLetter(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
    Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER -> true
    else -> false
}

internal fun isNumber(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
    else -> false
}

internal fun templateIds(root: JsonObject): Pair<Int, Int> {
    val sp = root["post_processor"]?.takeIf { it !is JsonNull }?.jsonObject?.get("special_tokens")?.jsonObject
        ?: throw IllegalArgumentException("нет post_processor.special_tokens")
    fun id(name: String) = sp[name]!!.jsonObject["ids"]!!.jsonArray[0].jsonPrimitive.int
    return id("[CLS]") to id("[SEP]")
}

internal fun padIdOf(root: JsonObject): Int =
    root["padding"]?.takeIf { it !is JsonNull }?.jsonObject?.get("pad_id")?.jsonPrimitive?.intOrNull ?: 0

internal fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

internal fun JsonElement?.obj(): JsonObject? = this?.takeIf { it !is JsonNull }?.jsonObject
