package splice.app.control.mount

import splice.client.transcript.CapturedBodyReader
import splice.client.transcript.CapturedRequest
import splice.client.transcript.CapturedRequestReader
import splice.client.transcript.CapturedSource
import splice.head.trace.TraceSessionSent
import splice.sessions.registry.SessionSource
import java.io.Reader

/** The full-body port stays compatible; transcript fallback chooses the trace's read-scoped stream instead. */
internal class SessionCaptures(
    private val sessions: SessionSource,
    private val trace: TraceSessionSent,
) : CapturedSource {
    override fun newest(sessionId: String): CapturedRequest? =
        head(sessionId)?.let { trace.newest(it, sessionId) }?.let { CapturedRequest(it.turnId, it.body) }

    override fun open(sessionId: String): CapturedRequestReader? {
        val sent = head(sessionId)?.let { trace.open(it, sessionId) } ?: return null
        return CapturedRequestReader(
            sent.turnId,
            object : CapturedBodyReader {
                override fun open(): Reader = sent.open()

                override fun close() = sent.close()
            },
            sent.heap,
        )
    }

    private fun head(sessionId: String): String? = sessions.read().firstOrNull { it.sessionId == sessionId }?.head
}
