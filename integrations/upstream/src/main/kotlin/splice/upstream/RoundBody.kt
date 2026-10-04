// NEW: one round's request body as something that becomes BYTES without becoming a String first.
//
// The transport needs UTF-8 bytes and nothing else. Every consumer that wants the text is
// conditional — a RoundInterceptor when one is configured, the wire trace when one is open, and
// Provider.amendBodyOnFailure on a failure — yet the direct path serialised the tree to a String on
// every round and the transport then encoded that String to bytes. On a 1.04 MB body that pair cost
// 2,997,608 bytes against 16,536 for a streamed encode (RequestParseAllocationTest), and
// JsonWire.write walks the same WireTree as JsonWire.string, so the two agree byte for byte by
// construction and law 8's request-byte golden cannot move.
//
// Two cases because the body arrives two ways: the runners hold the TREE they built, and an
// interceptor hands back TEXT it composed. Neither is converted to reach the other.
package splice.upstream

import kotlinx.serialization.json.JsonElement
import splice.core.util.JsonWire
import java.io.OutputStream

public sealed class RoundBody {
    /** The body as JSON text. On [Tree] the first read SERIALISES the whole body, so read it only
     *  where the text is genuinely needed; it is then retained, because a retry must not re-encode. */
    public abstract val text: String

    /** Writes the body's UTF-8 bytes to [output] without materialising [text]. */
    public abstract fun writeTo(output: OutputStream)

    /** The exact number of bytes [writeTo] will write, for sizing a buffer without encoding twice. */
    public abstract fun byteSize(): Long

    /** What a runner holds: the request tree it assembled. */
    public class Tree(private val element: JsonElement) : RoundBody() {
        override val text: String by lazy { JsonWire.string(element) }
        override fun writeTo(output: OutputStream): Unit = JsonWire.write(element, output)
        override fun byteSize(): Long = JsonWire.byteSize(element)
    }

    /** What an interceptor hands back: text it composed itself, which has no tree to stream from. */
    public class Text(override val text: String) : RoundBody() {
        override fun writeTo(output: OutputStream): Unit = output.write(text.toByteArray(Charsets.UTF_8))
        override fun byteSize(): Long = JsonWire.byteSize(text)
    }
}
