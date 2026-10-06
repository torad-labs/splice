// NEW: V4-444 bounded packed-literal validation, reusing the existing JSON string lexer across chunks.
package splice.head.trace.body

import splice.head.trace.ByteKinds
import splice.head.trace.Token
import splice.head.trace.Tokens

/** The existing JSON string lexer carries its escape state across independently validated chunks. */
internal class TraceLiteralScan {
    private var token: Token.Text? = null
    var ended = false
        private set

    /** A certificate carries the existing lexer's state, not a separately implemented grammar. */
    fun copy(): TraceLiteralScan = TraceLiteralScan().also {
        it.token = token?.copy()
        it.ended = ended
    }

    fun sameState(other: TraceLiteralScan): Boolean = ended == other.ended && when (val reading = token) {
        null -> other.token == null
        else -> other.token?.let(reading::sameState) == true
    }

    fun feed(bytes: ByteArray): Boolean = bytes.all(::feed)

    private fun feed(byte: Byte): Boolean {
        val reading = token
        return when {
            ended -> ByteKinds.space(byte)
            reading == null -> start(byte)
            else -> when (reading.feed(byte)) {
                Token.Fed.MORE -> true
                Token.Fed.DONE -> {
                    ended = true
                    true
                }
                Token.Fed.BEFORE, Token.Fed.BAD -> false
            }
        }
    }

    private fun start(byte: Byte): Boolean {
        if (ByteKinds.space(byte)) return true
        token = Tokens.key(byte, escapes = true)
        return token != null
    }
}
