// PORT-OF: bm25 2.3.2 embedder.rs/scorer.rs and fxhash 0.2.1, used by Codex rust-v0.164.0.
package splice.upstream.codemode.search

import splice.upstream.codemode.CodeModeSearchTokenizer
import kotlin.math.ln

/** Codex's sparse BM25 embeddings use float arithmetic, corpus-fitted length and FxHash32 token identities. */
internal class CodeModeBm25(corpus: List<String>) {
    private val documents = corpus.map { CodeModeSearchTokenizer.tokens(it).map(CodeModeTokenHash::hash) }
    private val averageLength = documents.sumOf { it.size.toLong() }.toDouble()
        .div(documents.size.coerceAtLeast(1)).toFloat().takeIf { it > 0f } ?: BM25_FALLBACK_LENGTH
    private val frequencies = HashMap<Int, Int>()
    private val embeddings = documents.map { tokens -> embed(tokens) }

    init {
        documents.forEach { tokens ->
            tokens.toSet().forEach { token ->
                frequencies[token] = frequencies.getOrDefault(token, 0) + 1
            }
        }
    }

    data class Match(val index: Int, val score: Float)

    /** Equal scores have no defined order in upstream's randomized hash-set traversal. */
    public fun matches(query: String): List<Match> {
        val tokens = CodeModeSearchTokenizer.tokens(query).map(CodeModeTokenHash::hash)
        return embeddings.mapIndexedNotNull { index, embedding ->
            var score = 0f
            for (token in tokens) {
                val frequency = (frequencies[token] ?: 0).toFloat()
                val idf = ln(1f + (documents.size.toFloat() - frequency + BM25_HALF) / (frequency + BM25_HALF))
                score += idf * (embedding[token] ?: 0f)
            }
            if (score > 0f) Match(index, score) else null
        }.sortedByDescending { it.score }
    }

    private fun embed(tokens: List<Int>): Map<Int, Float> {
        val counts = tokens.groupingBy { it }.eachCount()
        return counts.mapValues { (_, count) ->
            val frequency = count.toFloat()
            val numerator = frequency * (BM25_K1 + 1f)
            val denominator = frequency + BM25_K1 * (1f - BM25_B + BM25_B * (tokens.size.toFloat() / averageLength))
            numerator / denominator
        }
    }
}

/** std::hash::Hash for str writes UTF-8 bytes, then the 0xff string terminator. */
internal object CodeModeTokenHash {
    fun hash(token: String): Int {
        val bytes = token.encodeToByteArray()
        var hash = 0
        var index = 0
        while (index + FXHASH_WORD_BYTES <= bytes.size) {
            var word = 0
            for (offset in 0 until FXHASH_WORD_BYTES) {
                word = word or (bytes[index + offset].toUByte().toInt() shl (offset * Byte.SIZE_BITS))
            }
            hash = mix(hash, word)
            index += FXHASH_WORD_BYTES
        }
        while (index < bytes.size) hash = mix(hash, bytes[index++].toUByte().toInt())
        return mix(hash, UByte.MAX_VALUE.toInt())
    }

    private fun mix(hash: Int, word: Int): Int = (Integer.rotateLeft(hash, FXHASH_ROTATE) xor word) * FXHASH_SEED
}

private const val BM25_K1 = 1.2f
private const val BM25_B = 0.75f
private const val BM25_HALF = 0.5f
private const val BM25_FALLBACK_LENGTH = 256f
private const val FXHASH_ROTATE = 5
private const val FXHASH_SEED = 0x27220a95
private const val FXHASH_WORD_BYTES = 4
