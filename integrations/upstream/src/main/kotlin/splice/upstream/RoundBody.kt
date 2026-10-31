// NEW: one round's request body as something that becomes BYTES without becoming a String first.
//
// The transport needs UTF-8 bytes and nothing else. Every consumer that wants the text is
// conditional: a RoundInterceptor when one is configured, the wire trace when one is open, and
// Provider.amendBodyOnFailure on a failure. Yet the direct path serialised the tree to a String on
// every round, and the transport then encoded that String to bytes. JsonWire.write walks the same
// WireTree as JsonWire.string, so the two agree byte for byte by construction, and law 8's
// request-byte golden cannot move.
//
// [bytes] writes the body once and keeps one exact copy. The write reads the text through core's bulk
// UTF-8 writer when the text already exists, because a trace, a tap or an interceptor read it, and
// streams the tree otherwise. It lands in blocks that are never humongous. [byteSize] is the length
// that one write produced, so it costs nothing once [bytes] ran.
// why: the size used to be counted first, so every round walked its whole body for a number the write
// makes anyway, and existing text went through the JDK encoder's per-character buffer loop. The two
// were about 7.6% of the daemon's sampled CPU on Oct 25.
//
// Two cases because the body arrives two ways: the runners hold the TREE they built, and an
// interceptor hands back TEXT it composed. Neither is converted to reach the other.
package splice.upstream

import kotlinx.serialization.json.JsonElement
import splice.core.util.JsonWire
import splice.core.util.Utf8Wire
import java.io.OutputStream

// why: an 8 KiB first block holds a small body whole, and it is one drain of the UTF-8 writer's scratch.
private const val BODY_FIRST_BLOCK_BYTES = 8_192

// why: 256 KiB is under G1's humongous threshold, half a region, on every region size from 1 MiB up.
private const val BODY_BLOCK_CAP_BYTES = 262_144

// why: no body has a negative length, so this marks one that no write or count has measured yet.
private const val BODY_NOT_MEASURED = -1L

public sealed class RoundBody {
    /** The body as JSON text. On [Tree] the first read SERIALISES the whole body, so read it only
     *  where the text is genuinely needed; it is then retained, because a retry must not re-encode. */
    public abstract val text: String

    /** The length the last [bytes] wrote, or the count [byteSize] made before any write. Writing the same
     *  body again writes the same length. */
    @Volatile
    private var measured = BODY_NOT_MEASURED

    /** The body's UTF-8 bytes in one new array: written once into blocks and copied once, keeping its length. */
    public fun bytes(): ByteArray {
        val blocks = BodyBlocks()
        writeUtf8(blocks)
        return blocks.whole().also { measured = it.size.toLong() }
    }

    /** The number of bytes [bytes] returns: what its one write produced, free once it ran. Asked before [bytes]
     *  ever ran, it counts them without encoding, from the text when it exists and from the tree otherwise, and
     *  keeps that count. */
    public fun byteSize(): Long {
        val known = measured
        return if (known != BODY_NOT_MEASURED) known else countUtf8().also { measured = it }
    }

    /** Writes the body's UTF-8 to [output], once. */
    protected abstract fun writeUtf8(output: OutputStream)

    /** The number of bytes [writeUtf8] writes, counted without encoding them. */
    protected abstract fun countUtf8(): Long

    /** Text goes through core's writer, which spells every character as the JVM encoder does, the one-byte '?'
     *  for a lone surrogate included, and reads the String in bulk copies rather than a character at a time. */
    protected fun writeText(text: String, output: OutputStream) {
        val utf8 = Utf8Wire(output)
        utf8.write(text)
        utf8.drain()
    }

    /** What a runner holds: the request tree it assembled. [element] is readable so an interceptor
     *  that reads the request reads this tree instead of parsing its text back into another one. */
    public class Tree(public val element: JsonElement) : RoundBody() {
        private val serialized = lazy { JsonWire.string(element) }
        override val text: String by serialized

        /** Reuse text an interceptor or trace already needed, including its original lone surrogates.
         *  Otherwise stream the tree directly; neither path builds a String to reach the bytes. */
        override fun writeUtf8(output: OutputStream) {
            if (serialized.isInitialized()) writeText(serialized.value, output) else JsonWire.write(element, output)
        }

        override fun countUtf8(): Long =
            if (serialized.isInitialized()) JsonWire.byteSize(serialized.value) else JsonWire.byteSize(element)
    }

    /** What an interceptor hands back: text it composed itself, which has no tree to stream from. */
    public class Text(override val text: String) : RoundBody() {
        override fun writeUtf8(output: OutputStream) {
            writeText(text, output)
        }

        override fun countUtf8(): Long = JsonWire.byteSize(text)
    }

    /** Where [bytes] writes a body before its one exact copy: blocks from 8 KiB doubling to a 256 KiB cap, so a
     *  body of any size is held without regrowing one array, and no block is ever humongous. */
    private class BodyBlocks : OutputStream() {
        private val full = ArrayList<ByteArray>()
        private var block = ByteArray(BODY_FIRST_BLOCK_BYTES)
        private var filled = 0
        private var total = 0L

        override fun write(byte: Int) {
            if (filled == block.size) next()
            block[filled++] = byte.toByte()
            total++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var from = offset
            val end = offset + length
            while (from < end) {
                if (filled == block.size) next()
                val step = minOf(end - from, block.size - filled)
                System.arraycopy(bytes, from, block, filled, step)
                filled += step
                from += step
            }
            total += length
        }

        /** Every byte written, in order, in one array of exactly their number. */
        fun whole(): ByteArray {
            val bytes = ByteArray(Math.toIntExact(total))
            var at = 0
            for (done in full) {
                System.arraycopy(done, 0, bytes, at, done.size)
                at += done.size
            }
            System.arraycopy(block, 0, bytes, at, filled)
            return bytes
        }

        private fun next() {
            full += block
            block = ByteArray(minOf(2 * block.size, BODY_BLOCK_CAP_BYTES))
            filled = 0
        }
    }
}
