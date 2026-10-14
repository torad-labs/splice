package splice.client.transcript.capture

import splice.core.memory.HeapJson
import splice.sessions.transcript.TranscriptPage
import java.util.IdentityHashMap

// why: the escaped page retains its shell, message-list backing storage and cursor bookkeeping beyond text payloads.
private const val CAPTURE_PAGE_OWNER_BYTES = 4_096L

// why: each projected message retains its object, tool/source facts and list reference separately from shared strings.
private const val CAPTURE_MESSAGE_OWNER_BYTES = 256L

/** One page's escaped graph, not its decoding peak. A shared assistant id is counted once, not once per block. */
internal class CapturedPageWeight {
    private val strings = IdentityHashMap<String, Boolean>()

    fun of(page: TranscriptPage): Long {
        var bytes = CAPTURE_PAGE_OWNER_BYTES
        for (message in page.messages) {
            bytes = HeapJson.add(bytes, CAPTURE_MESSAGE_OWNER_BYTES + text(message.text))
            bytes = HeapJson.add(bytes, text(message.messageId))
            bytes = HeapJson.add(bytes, text(message.toolUse.id))
            bytes = HeapJson.add(bytes, text(message.toolUse.name))
        }
        bytes = HeapJson.add(bytes, text(page.sessionId) + text(page.path))
        return HeapJson.add(bytes, text(page.next) + text(page.earlier))
    }

    private fun text(value: String?): Long =
        if (value == null || strings.put(value, true) != null) 0L else HeapJson.text(value)
}
