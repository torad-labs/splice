// NEW: detached payload is bounded by protocol bytes, never an arbitrary delta-count queue.
package splice.provider.codex.stream

import splice.upstream.codemode.CodeModeLimits
import splice.upstream.sse.WireSink

internal fun interface BufferedSinkWrite {
    suspend fun emit(sink: WireSink)
}

/** What a detached write spends while the round waits for its next client step. */
internal enum class DetachedSpend {
    /** Model output: past the byte budget the round fails. */
    MODEL,

    /** splice's live script text (V4-456): dropped past a budget of its own. */
    NOTICE_TEXT,

    /** A live script's signature or close: never dropped, so a block a kept delta opened is always signed
     *  and closed. Charged to the notice budget. A round carries one exec call (CodeModeSourceCapture), so it
     *  queues one signature-and-close pair, which does nothing on drain when no kept delta opened the block. */
    NOTICE_FRAME,
}

/** Access is serialized by the switching sink's attachment mutex. */
internal class CodeModeSinkWrites {
    private val pending = ArrayDeque<BufferedSinkWrite>()
    private var pendingBytes = 0

    /** splice's own live script keeps its own count, so it never spends the budget model output is held to. */
    private var noticeBytes = 0

    // Even empty deltas retain an event and its closure. Charge the smallest wire frame envelope.
    private val frameHeaderBytes =
        """{"type":"content_block_delta","index":0,"delta":{}}""".encodeToByteArray().size

    /** A detached write waits for the next client step, spending as [spend] says. Live script text is
     *  dropped past its own budget, because a 65,536-character script sent one character a delta outweighs
     *  a budget in envelopes alone; its signature and close are always kept. */
    suspend fun deliver(sink: WireSink?, bytes: Int, spend: DetachedSpend, action: BufferedSinkWrite) {
        if (sink != null) {
            action.emit(sink)
            return
        }
        when (spend) {
            DetachedSpend.MODEL -> {
                val available = CodeModeLimits.MAX_FRAME_BYTES - pendingBytes - frameHeaderBytes
                require(bytes <= available) { "detached stream exceeds byte budget" }
                pendingBytes += bytes + frameHeaderBytes
            }
            DetachedSpend.NOTICE_TEXT -> {
                if (bytes > CodeModeLimits.MAX_FRAME_BYTES - noticeBytes - frameHeaderBytes) return
                noticeBytes += bytes + frameHeaderBytes
            }
            DetachedSpend.NOTICE_FRAME -> noticeBytes += bytes + frameHeaderBytes
        }
        pending.addLast(action)
    }

    /** Drops the writes still waiting for a client step: they belong to a draft no client will see. */
    fun discard() {
        pending.clear()
        pendingBytes = 0
        noticeBytes = 0
    }

    suspend fun drain(sink: WireSink) {
        while (pending.isNotEmpty()) {
            pending.first().emit(sink)
            pending.removeFirst()
        }
        pendingBytes = 0
        noticeBytes = 0
    }
}
