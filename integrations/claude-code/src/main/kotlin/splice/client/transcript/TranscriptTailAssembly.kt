// NEW: V4-444 — tail windows use the page's exact parse, merge and redaction rules.
package splice.client.transcript

import kotlinx.serialization.json.JsonObject
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptRole

/** Parsing stays in TranscriptReader; the tail never owns a second JSON or record policy. */
internal fun interface TranscriptLineParser {
    fun parse(bytes: ByteArray): JsonObject?
}

internal data class TailSelection(val message: TranscriptMessage?, val complete: Boolean)

/** The first line of a non-origin window is incomplete and cannot contribute a partial message. */
internal class TranscriptTailAssembly(
    private val parser: TranscriptLineParser,
    private val redaction: TranscriptRedaction,
) {
    fun select(bytes: ByteArray, origin: Boolean): TailSelection {
        val first = if (origin) 0 else bytes.indexOf('\n'.code.toByte()) + 1
        if (!origin && first == 0) return TailSelection(null, false)
        val assembly = PageAssembly(0, Int.MAX_VALUE, redaction)
        bytes.copyOfRange(first, bytes.size).toString(Charsets.UTF_8).lineSequence().forEach {
            assembly.accept(parser.parse(it.toByteArray(Charsets.UTF_8)))
        }
        val messages = assembly.finish()
        val at = messages.indexOfLast(::activity)
        val last = messages.getOrNull(at)
        // A different earlier message proves that the chosen reply's leading blocks were included.
        // At file origin there are no missing leading blocks, even for a single-message transcript.
        val ready = ready(messages.take(at.coerceAtLeast(0)), last)
        val complete = origin || ready
        return TailSelection(last, complete)
    }

    /** What the session said, was told or called: a tool result and a system note are neither. */
    private fun activity(message: TranscriptMessage): Boolean =
        message.role == TranscriptRole.USER || message.role == TranscriptRole.ASSISTANT

    private fun ready(before: List<TranscriptMessage>, last: TranscriptMessage?): Boolean {
        if (last == null) return false
        return before.any { last.messageId == null || it.messageId != last.messageId }
    }
}
