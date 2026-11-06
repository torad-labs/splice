// NEW: detached payload is bounded by protocol bytes, never an arbitrary delta-count queue.
package splice.provider.codex.stream

import kotlinx.coroutines.currentCoroutineContext
import splice.core.util.MonoClock
import splice.upstream.codemode.CodeModeInvocationContext
import splice.upstream.codemode.CodeModeInvocationContextKey
import splice.upstream.codemode.CodeModeLimits
import splice.upstream.sse.WireSink

internal fun interface BufferedSinkWrite {
    suspend fun emit(sink: WireSink)
}

/** Access is serialized by the switching sink's attachment mutex. */
internal class CodeModeSinkWrites {
    private val pending = ArrayDeque<ObservedBufferedWrite>()
    private var pendingBytes = 0

    // Even empty deltas retain an event and its closure. Charge the smallest wire frame envelope.
    private val frameHeaderBytes =
        """{"type":"content_block_delta","index":0,"delta":{}}""".encodeToByteArray().size

    /** A detached write waits for the next client step; past the byte budget the round fails. */
    suspend fun deliver(sink: WireSink?, bytes: Int, action: BufferedSinkWrite) {
        if (sink != null) {
            action.emit(sink)
            return
        }
        val available = CodeModeLimits.MAX_FRAME_BYTES - pendingBytes - frameHeaderBytes
        require(bytes <= available) { "detached stream exceeds byte budget" }
        pendingBytes += bytes + frameHeaderBytes
        val context = currentCoroutineContext()[CodeModeInvocationContextKey]
        val retainedBytes = bytes + frameHeaderBytes
        context?.buffered(retainedBytes)
        pending.addLast(
            ObservedBufferedWrite(
                retainedBytes,
                MonoClock.nowMs(),
                context,
                if (context == null) {
                    action
                } else {
                    BufferedSinkWrite { target ->
                        if (context.isActive) context.within { action.emit(target) }
                    }
                },
            ),
        )
    }

    /** Drops the writes still waiting for a client step: they belong to a draft no client will see. */
    fun discard() {
        pending.forEach { it.context?.discarded(it.bytes) }
        pending.clear()
        pendingBytes = 0
    }

    suspend fun drain(sink: WireSink) {
        while (pending.isNotEmpty()) {
            val write = pending.first()
            write.action.emit(sink)
            write.context?.delivered(write.bytes, MonoClock.nowMs() - write.queuedAt)
            pending.removeFirst()
        }
        pendingBytes = 0
    }
}

private data class ObservedBufferedWrite(
    val bytes: Int,
    val queuedAt: Long,
    val context: CodeModeInvocationContext?,
    val action: BufferedSinkWrite,
)
