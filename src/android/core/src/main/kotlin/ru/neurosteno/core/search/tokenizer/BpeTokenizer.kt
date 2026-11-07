package ru.neurosteno.core.search.tokenizer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Byte-level BPE (USER2-small / ModernBERT): нормализация NFC, добавленные токены (пробельные серии и т. п.), предтокенизация
 * регулярным выражением GPT-2 (`'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+`, реализована сканером --
 * без `\p{..}`/lookahead, которые на JVM и Android ведут себя по-разному), отображение байтов GPT-2 и слияния по рангам.
 */
internal class BpeTokenizer(root: JsonObject) : TextTokenizer {
    private val model = root["model"]!!.jsonObject
    private val vocab: Map<String, Int> = model["vocab"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }
    private val added = AddedVocab(root["added_tokens"]?.jsonArray ?: JsonArray(emptyList()))
    private val ranks = HashMap<Long, Int>(1 shl 17)
    private val mergedId = HashMap<Long, Int>(1 shl 17)
    private val cls: Int
    private val sep: Int
    override val padId: Int = padIdOf(root)
    private val doNfc: Boolean = root["normalizer"].obj()?.get("type")?.jsonPrimitive?.content == "NFC"
    private val byteToChar = IntArray(256)
    private val cache = object : LinkedHashMap<String, IntArray>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?) = size > 20_000
    }

    init {
        val (c, s) = templateIds(root)
        cls = c
        sep = s
        val pre = root["pre_tokenizer"].obj()
        require(pre?.get("type")?.jsonPrimitive?.content == "ByteLevel") { "ожидается ByteLevel" }
        require(pre["add_prefix_space"]?.jsonPrimitive?.content != "true") { "add_prefix_space не поддержан" }
        // отображение байтов GPT-2: печатные байты -> сами, остальные -> U+0100 + n
        val printable = (33..126) + (161..172) + (174..255)
        var n = 0
        for (b in 0..255) byteToChar[b] = if (b in printable) b else 256 + n++
        var rank = 0
        for (m in model["merges"]!!.jsonArray) {
            val pair = if (m is JsonArray) m[0].jsonPrimitive.content to m[1].jsonPrimitive.content
            else m.jsonPrimitive.content.split(' ', limit = 2).let { it[0] to it[1] }
            val a = vocab[pair.first]
            val b = vocab[pair.second]
            val ab = vocab[pair.first + pair.second]
            if (a != null && b != null && ab != null) {
                val key = (a.toLong() shl 32) or b.toLong()
                ranks.putIfAbsent(key, rank)
                mergedId.putIfAbsent(key, ab)
            }
            rank++
        }
    }

    private fun normalize(s: String) = if (doNfc) nfc(s) else s

    /** Предтокены: границы [from, to) в массиве кодовых точек. */
    private fun split(cps: IntArray): List<IntArray> {
        val out = ArrayList<IntArray>()
        val n = cps.size
        var i = 0
        fun ws(k: Int) = isRustWhitespace(cps[k])
        while (i < n) {
            val c = cps[i]
            var end = -1
            // сокращения 's 't 're 've 'm 'll 'd
            if (c == '\''.code && i + 1 < n) {
                val c1 = cps[i + 1]
                val c2 = if (i + 2 < n) cps[i + 2] else -1
                end = when {
                    c1 == 's'.code || c1 == 't'.code || c1 == 'm'.code || c1 == 'd'.code -> i + 2
                    (c1 == 'r'.code && c2 == 'e'.code) || (c1 == 'v'.code && c2 == 'e'.code) || (c1 == 'l'.code && c2 == 'l'.code) -> i + 3
                    else -> -1
                }
            }
            if (end < 0) {
                val st = if (c == ' '.code && i + 1 < n) i + 1 else i // необязательный ровно один пробел U+0020
                val d = cps[st]
                val kind = when { isLetter(d) -> 1; isNumber(d) -> 2; !ws(st) -> 3; else -> 0 }
                if (kind != 0) {
                    var j = st
                    while (j < n) {
                        val e = cps[j]
                        val ok = when (kind) { 1 -> isLetter(e); 2 -> isNumber(e); else -> !isRustWhitespace(e) && !isLetter(e) && !isNumber(e) }
                        if (!ok) break
                        j++
                    }
                    end = j
                }
            }
            if (end < 0) {
                // \s+(?!\S) | \s+ : серия пробельных; если за ней идёт непробельный, последний пробельный остаётся следующему токену
                var j = i
                while (j < n && ws(j)) j++
                end = if (j >= n) j else if (j - i >= 2) j - 1 else j
            }
            out += cps.copyOfRange(i, end)
            i = end
        }
        return out
    }

    private fun bpe(word: IntArray): IntArray {
        val key = String(word, 0, word.size)
        cache[key]?.let { return it }
        // символы слова: байты UTF-8 -> символы GPT-2 -> id из vocab (отсутствующие пропускаются, как в HF при unk_token = null)
        val bytes = key.toByteArray(Charsets.UTF_8)
        val syms = ArrayList<Int>(bytes.size)
        for (b in bytes) {
            val ch = String(Character.toChars(byteToChar[b.toInt() and 0xFF]))
            vocab[ch]?.let { syms += it }
        }
        var cur = syms.toIntArray()
        while (cur.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestPos = -1
            for (p in 0 until cur.size - 1) {
                val r = ranks[(cur[p].toLong() shl 32) or cur[p + 1].toLong()] ?: continue
                if (r < bestRank) { bestRank = r; bestPos = p }
            }
            if (bestPos < 0) break
            val merged = mergedId.getValue((cur[bestPos].toLong() shl 32) or cur[bestPos + 1].toLong())
            val next = IntArray(cur.size - 1)
            for (p in 0 until bestPos) next[p] = cur[p]
            next[bestPos] = merged
            for (p in bestPos + 1 until next.size) next[p] = cur[p + 1]
            cur = next
        }
        cache[key] = cur
        return cur
    }

    override fun encode(text: String, maxLen: Int): IntArray {
        val ids = ArrayList<Int>()
        for (p in added.extract(text, ::normalize)) {
            if (p.id != null) { ids += p.id; continue }
            for (w in split(p.text.codePoints().toArray())) for (t in bpe(w)) ids += t
        }
        val keep = if (maxLen > 0) minOf(ids.size, maxOf(0, maxLen - 2)) else ids.size
        val out = IntArray(keep + 2)
        out[0] = cls
        for (i in 0 until keep) out[1 + i] = ids[i]
        out[keep + 1] = sep
        return out
    }
}
