// NEW: one round's request body as something that becomes BYTES without becoming a String first.
//
// The transport needs UTF-8 bytes and nothing else. Every consumer that wants the text is
// conditional: a RoundInterceptor when one is configured, the wire trace when one is open, and
// Provider.amendBodyOnFailure on a failure. Yet the direct path serialised the tree to a String on
// every round, and the transport then encoded that String to bytes. JsonWire.write walks the same
// WireTree as JsonWire.string, so the two agree byte for byte by construction, and law 8's
// request-byte golden cannot move.
//
// [bytes] is the one copy the transport keeps: an array of exactly [byteSize] bytes, filled in place.
// On a 0.92 MB body that is about one body of allocation either way a body arrives, where the
// buffered encode it replaced kept two copies of a tree and three of text (RequestBodyAllocationTest).
//
// Two cases because the body arrives two ways: the runners hold the TREE they built, and an
// interceptor hands back TEXT it composed. Neither is converted to reach the other.
package splice.upstream

import kotlinx.serialization.json.JsonElement
import splice.core.util.JsonWire
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

public sealed class RoundBody {
    /** The body as JSON text. On [Tree] the first read SERIALISES the whole body, so read it only
     *  where the text is genuinely needed; it is then retained, because a retry must not re-encode. */
    public abstract val text: String

    /** The body's UTF-8 bytes in one new array of exactly [byteSize] bytes. */
    public abstract fun bytes(): ByteArray

    /** The exact number of bytes [bytes] returns, computed once without encoding to an array. */
    public abstract fun byteSize(): Long

    /** What a runner holds: the request tree it assembled. [element] is readable so an interceptor
     *  that reads the request reads this tree instead of parsing its text back into another one. */
    public class Tree(public val element: JsonElement) : RoundBody() {
        private val serialized = lazy { JsonWire.string(element) }
        private val size: Long by lazy {
            if (serialized.isInitialized()) JsonWire.byteSize(serialized.value) else JsonWire.byteSize(element)
        }
        override val text: String by serialized

        /** Reuse text an interceptor or trace already needed, including its original lone surrogates.
         *  Otherwise stream the tree directly; neither path retains a mutable byte array. */
        override fun bytes(): ByteArray =
            if (serialized.isInitialized()) {
                TextBytes.encode(serialized.value, size)
            } else {
                ExactBytes(size).also { JsonWire.write(element, it) }.filled()
            }

        override fun byteSize(): Long = size
    }

    /** What an interceptor hands back: text it composed itself, which has no tree to stream from. */
    public class Text(override val text: String) : RoundBody() {
        private val size: Long by lazy { JsonWire.byteSize(text) }

        override fun bytes(): ByteArray = TextBytes.encode(text, size)

        override fun byteSize(): Long = size
    }

    /** An output stream over one array sized in advance, so the bytes land where the caller keeps
     *  them, with no growth and no closing copy. Writing past the end, or finishing short of it,
     *  means JsonWire.byteSize and JsonWire.write disagreed, and both fail loudly. */
    private class ExactBytes(size: Long) : OutputStream() {
        private val bytes = ByteArray(Math.toIntExact(size))
        private var position = 0

        override fun write(value: Int) {
            bytes[position] = value.toByte()
            position++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            System.arraycopy(buffer, offset, bytes, position, length)
            position += length
        }

        fun filled(): ByteArray {
            check(position == bytes.size) { "wrote $position of ${bytes.size} sized bytes" }
            return bytes
        }
    }

    /** ASCII text uses the JDK's single copy. Wider text goes straight into an exact array.
     *  REPLACE preserves String.getBytes' one-byte '?' for a lone surrogate; decoding those bytes
     *  back to text would not preserve the original String. */
    private object TextBytes {
        fun encode(text: String, size: Long): ByteArray {
            if (size == text.length.toLong()) return text.toByteArray(Charsets.UTF_8)
            val bytes = ByteArray(Math.toIntExact(size))
            val target = ByteBuffer.wrap(bytes)
            val encoder = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            val encoded = encoder.encode(CharBuffer.wrap(text), target, true)
            val flushed = encoder.flush(target)
            check(encoded.isUnderflow && flushed.isUnderflow && !target.hasRemaining()) {
                "encoded ${target.position()} of ${bytes.size} sized bytes"
            }
            return bytes
        }
    }
}
