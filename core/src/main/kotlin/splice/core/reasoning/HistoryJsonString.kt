package splice.core.reasoning

import splice.core.util.JsonWire
import java.security.MessageDigest

/** Streams the existing length-prefixed JSON token without an escaped copy of a whole tool result.
 * The digest consumes UTF-16 code units, including lone surrogates, not UTF-8 wire text. */
internal class HistoryJsonString(private val encoding: ByteArray) {
    private val chars = CharArray(encoding.size / Char.SIZE_BYTES)

    fun write(text: String, digest: MessageDigest) {
        var length = 2L + text.length
        chunks(text) { count ->
            for (index in 0 until count) {
                val escape = JsonWire.escape(chars[index])
                if (escape != null) length += escape.length - 1
            }
        }
        val tokenLength = Math.toIntExact(length)
        encoding[0] = 1
        for (index in 0 until Int.SIZE_BYTES) {
            val shift = (Int.SIZE_BYTES - 1 - index) * Byte.SIZE_BITS
            encoding[index + 1] = (tokenLength ushr shift).toByte()
        }
        var used = 1 + Int.SIZE_BYTES
        fun append(character: Char) {
            if (used + Char.SIZE_BYTES > encoding.size) {
                digest.update(encoding, 0, used)
                used = 0
            }
            encoding[used++] = (character.code ushr Byte.SIZE_BITS).toByte()
            encoding[used++] = character.code.toByte()
        }
        append('"')
        chunks(text) { count ->
            for (index in 0 until count) {
                val character = chars[index]
                val escape = JsonWire.escape(character)
                if (escape == null) append(character) else escape.forEach(::append)
            }
        }
        append('"')
        digest.update(encoding, 0, used)
    }

    private inline fun chunks(text: String, consume: (Int) -> Unit) {
        var start = 0
        while (start < text.length) {
            val count = minOf(chars.size, text.length - start)
            text.toCharArray(chars, 0, start, start + count)
            consume(count)
            start += count
        }
    }
}
