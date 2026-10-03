package io.github.weslocke.outrider

import java.io.InputStream
import java.text.Normalizer

/**
 * Splits text into the keyword-spotting model's word pieces the way sentencepiece does ("HEY VESPA" ->
 * ▁HE Y ▁ VE S P A), so the wake word can be any word the user types. The model's "bpe.model" is in fact a
 * sentencepiece UNIGRAM model: each word is split into the pieces whose log-probabilities add up highest (Viterbi).
 * Reads the ModelProto itself; checked against sentencepiece's own output in the unit tests.
 */
class Bpe private constructor(private val scores: Map<String, Float>) {

    /** Pieces for [text]: uppercased, accents dropped, only letters, apostrophes and spaces kept. */
    fun encode(text: String): List<String> {
        val words = clean(text).split(' ').filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (w in words) out += encodeWord(SPACE + w)
        return out
    }

    /** Unigram Viterbi: the split of [word] into known pieces with the highest total score. */
    private fun encodeWord(word: String): List<String> {
        val n = word.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (i in 1..n) {
            for (j in maxOf(0, i - maxLen) until i) {
                if (best[j] == Double.NEGATIVE_INFINITY) continue
                // an unknown single character still splits, at sentencepiece's penalty below the rarest piece
                val score = scores[word.substring(j, i)]?.toDouble() ?: if (i - j == 1) unkScore else continue
                if (best[j] + score > best[i]) {
                    best[i] = best[j] + score
                    from[i] = j
                }
            }
        }
        val pieces = ArrayDeque<String>()
        var i = n
        while (i > 0) {
            pieces.addFirst(word.substring(from[i], i))
            i = from[i]
        }
        return pieces.toList()
    }

    private val maxLen = scores.keys.maxOfOrNull { it.length } ?: 1
    private val unkScore = (scores.values.minOrNull() ?: 0f).toDouble() - 10.0

    /** Whether every piece is one the model knows (an unknown character would make the keyword unusable). */
    fun known(pieces: List<String>, tokens: Set<String>) = pieces.all { it in tokens }

    companion object {
        const val SPACE = "▁"

        fun clean(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .uppercase()
                .replace(Regex("[^A-Z' ]+"), " ")   // the model has no digits
                .replace(Regex(" +"), " ")
                .trim()

        /** Reads the pieces and scores of a sentencepiece model (normal pieces only). */
        fun load(input: InputStream): Bpe {
            val bytes = input.readBytes()
            val scores = HashMap<String, Float>()
            val top = Proto(bytes, 0, bytes.size)
            while (top.more()) {
                val (field, wire) = top.key()
                if (field == 1 && wire == 2) {
                    val (start, end) = top.lengthDelimited()
                    var piece: String? = null
                    var score = 0f
                    var type = 1
                    val p = Proto(bytes, start, end)
                    while (p.more()) {
                        val (f, w) = p.key()
                        when {
                            f == 1 && w == 2 -> p.lengthDelimited().let { (s, e) -> piece = String(bytes, s, e - s, Charsets.UTF_8) }
                            f == 2 && w == 5 -> score = p.float32()
                            f == 3 && w == 0 -> type = p.varint().toInt()
                            else -> p.skip(w)
                        }
                    }
                    if (piece != null && type == 1) scores[piece!!] = score
                } else top.skip(wire)
            }
            return Bpe(scores)
        }
    }

    /** Just enough protobuf wire-format reading for ModelProto. */
    private class Proto(private val b: ByteArray, private var pos: Int, private val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val x = b[pos++].toInt() and 0xFF
                result = result or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
            }
        }
        fun key(): Pair<Int, Int> = varint().let { (it ushr 3).toInt() to (it and 7).toInt() }
        fun lengthDelimited(): Pair<Int, Int> {
            val n = varint().toInt()
            val s = pos
            pos += n
            return s to pos
        }
        fun float32(): Float {
            val bits = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8) or
                ((b[pos + 2].toInt() and 0xFF) shl 16) or ((b[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return java.lang.Float.intBitsToFloat(bits)
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> lengthDelimited()
                5 -> pos += 4
                else -> throw IllegalStateException("protobuf wire type $wire")
            }
        }
    }
}
