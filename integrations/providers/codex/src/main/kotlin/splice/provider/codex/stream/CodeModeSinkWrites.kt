// NEW: detached payload is bounded by protocol bytes, never an arbitrary delta-count queue.
package splice.provider.codex.stream

import splice.upstream.codemode.CodeModeLimits
import splice.upstream.sse.WireSink

internal fun interface BufferedSinkWrite {
    suspend fun emit(sink: WireSink)
}

/** Access is serialized by the switching sink's attachment mutex. */
internal class CodeModeSinkWrites {
    private val pending = ArrayDeque<BufferedSinkWrite>()
    private var pendingBytes = 0

    // Even empty deltas retain an event and its closure. Charge the smallest wire frame envelope.
    private val frameHeaderBytes =
        """{"type":"content_block_delta","index":0,"delta":{}}""".encodeToByteArray().size

    suspend fun deliver(sink: WireSink?, bytes: Int, action: BufferedSinkWrite) {
        if (sink != null) {
            action.emit(sink)
        } else {
            val available = CodeModeLimits.MAX_FRAME_BYTES - pendingBytes - frameHeaderBytes
            require(bytes <= available) { "detached stream exceeds byte budget" }
            pendingBytes += bytes + frameHeaderBytes
            pending.addLast(action)
        }
    }

    suspend fun drain(sink: WireSink) {
        while (pending.isNotEmpty()) {
            pending.first().emit(sink)
            pending.removeFirst()
        }
        pendingBytes = 0
    }
}
