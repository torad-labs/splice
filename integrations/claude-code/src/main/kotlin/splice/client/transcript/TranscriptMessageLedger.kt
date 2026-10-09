// NEW: V4-160 — the messages a page has made and the numbers they took, split out of PageAssembly so the assembly only
// reads records and this owns turning what they said into numbered, redacted messages.
package splice.client.transcript

import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptRole
import splice.sessions.transcript.TranscriptToolUse

/** One tool call the client recorded inside an assistant message. */
internal data class RecordedCall(val id: String?, val name: String, val input: String)

/** An assistant message still being read: the client writes one message across several records. */
internal class PendingAssistant(val id: String?, val ts: Long?, val at: Long) {
    val texts = mutableListOf<String>()
    val calls = mutableListOf<RecordedCall>()

    /** The message's text blocks as one text, in the order the client wrote them. */
    fun joined(): String = texts.joinToString("\n\n")
}

/** The messages one page has made. [byOffset]: a message is numbered by where its record starts in the file, the offset
 *  times [PER_RECORD] plus its place among the messages that record made, so pages read from either end never repeat or
 *  reorder an index; otherwise by the next index in order. */
internal class MessageLedger(
    firstIndex: Long,
    private val redaction: TranscriptRedaction,
    private val byOffset: Boolean,
) {
    private val messages = mutableListOf<TranscriptMessage>()
    private val toolNames = HashMap<String, String>()
    private val offsets = OffsetNumbering()

    var nextIndex: Long = firstIndex
        private set

    val size: Int get() = messages.size

    fun all(): List<TranscriptMessage> = messages

    /** Completed messages only, handed over and forgotten. */
    fun drain(): List<TranscriptMessage> = messages.toList().also { messages.clear() }

    fun rememberTool(id: String, name: String) {
        toolNames[id] = name
    }

    fun text(at: Long, role: TranscriptRole, ts: Long?, text: String) {
        messages += TranscriptMessage(place(at), role, ts, redaction.shown(text))
    }

    fun toolResult(at: Long, ts: Long?, text: String, toolUseId: String?) {
        messages += TranscriptMessage(
            place(at),
            TranscriptRole.TOOL,
            ts,
            redaction.shown(text),
            toolUse = TranscriptToolUse(toolUseId?.let(toolNames::get), result = true, id = toolUseId),
        )
    }

    /** A finished assistant message: its text as one message, then one per recorded call, all at its first record. */
    fun assistant(done: PendingAssistant) {
        if (done.texts.isNotEmpty()) {
            val text = redaction.shown(done.joined())
            messages += TranscriptMessage(place(done.at), TranscriptRole.ASSISTANT, done.ts, text, messageId = done.id)
        }
        for (call in done.calls) {
            messages += TranscriptMessage(
                place(done.at),
                TranscriptRole.ASSISTANT,
                done.ts,
                redaction.shown(call.input),
                toolUse = TranscriptToolUse(call.name, result = false, id = call.id),
                messageId = done.id,
            )
        }
    }

    private fun place(from: Long): Long {
        val index = if (byOffset) offsets.indexOf(from) else nextIndex
        nextIndex += 1
        return index
    }
}

/** Numbers messages by where their record starts in the file: the offset times [PER_RECORD] plus the message's place
 *  among the messages that same record made. */
private class OffsetNumbering {
    private var sameAt = -1L
    private var lastAt = -1L

    fun indexOf(from: Long): Long {
        sameAt = if (from == lastAt) sameAt + 1 else 0
        lastAt = from
        return from * PER_RECORD + sameAt
    }
}
