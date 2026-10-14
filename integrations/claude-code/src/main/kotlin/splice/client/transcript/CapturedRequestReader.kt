package splice.client.transcript

import splice.core.memory.HeapReservations
import java.io.Reader
import java.io.StringReader

/** Opens a fresh pass over one immutable captured body. Child readers close before their source. */
public fun interface CapturedBodyReader : AutoCloseable {
    public fun open(): Reader

    override fun close() {}
}

/** Repeatable streaming capture and its existing heap admission view, owned by one transcript page read. */
public class CapturedRequestReader(
    public val turnId: String,
    private val body: CapturedBodyReader,
    public val heap: HeapReservations? = null,
) : AutoCloseable {
    internal fun open(): Reader = body.open()

    internal fun text(): CapturedText {
        val input = open()
        var ready = false
        return splice.core.util.Cancellables.withCleanup({ if (!ready) input.close() }) {
            CapturedText(input, heap).also { ready = true }
        }
    }

    override fun close() {
        body.close()
    }

    internal object Inline {
        fun of(sent: CapturedRequest): CapturedRequestReader =
            CapturedRequestReader(sent.turnId, CapturedBodyReader { StringReader(sent.body) })
    }
}
