// NEW: a chunked body is charged for the bytes that arrived, not for the cap it might still reach.
package splice.http.ingress

import splice.core.memory.HeapWeights
import splice.core.wire.HttpStatus

/** What a chunked body is charged before its first chunk: enough for its headers and a small body. */
internal const val CHUNKED_FLOOR_BYTES = 64 * 1024L

/** Why a body was refused after the request was already admitted. */
internal data class BodyRefusal(val status: Int, val message: String)

/**
 * The charge of ONE request whose length is not declared. Content-Length bodies are charged for their whole
 * length at the headers; a chunked one starts at [CHUNKED_FLOOR_BYTES] and grows with each chunk, so four small
 * chunked requests no longer fill a budget that one cap-sized charge each would have exhausted.
 */
internal class ChunkedBody(val entry: IngressRequest, private val capBytes: Long) {
    private var received = 0L

    /** Charge [bytes] more; null means they fit, otherwise the refusal to answer with. */
    fun receive(bytes: Int): BodyRefusal? {
        received += bytes.toLong()
        val lease = checkNotNull(entry.lease)
        val want = HeapWeights.ingress(maxOf(received, minOf(CHUNKED_FLOOR_BYTES, capBytes)))
        return when {
            received > capBytes -> BodyRefusal(HttpStatus.CONTENT_TOO_LARGE, "request body exceeds $capBytes bytes")
            want > lease.bytes && !lease.resize(want) -> BodyRefusal(HttpStatus.OVERLOADED, "gateway busy; retry")
            else -> null
        }
    }
}
