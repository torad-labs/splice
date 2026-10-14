// Sessions' read-only fallback when no primary transcript exists. Captured pages keep complete API-message
// groups, the file reader's byte ceiling, and the same credential masking. File cursors never fall back.
package splice.client.transcript

import splice.core.util.Cancellables
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptLookup
import java.nio.file.Path

/** One captured request: the turn it was sent on and the body exactly as the client sent it. */
public data class CapturedRequest(val turnId: String, val body: String)

/** The newest captured request of a session, or null when the trace keeps none. */
public fun interface CapturedSource {
    public fun newest(sessionId: String): CapturedRequest?
}

public class CapturedTranscripts(
    private val delegate: SessionTranscripts,
    private val captured: CapturedSource,
) : SessionTranscripts by delegate {
    private val pages = CapturedPages()
    private val validSessionId = Regex("[A-Za-z0-9_-]{1,128}")

    override fun page(sessionId: String, roots: List<Path>, cursor: String?, limit: Int): TranscriptLookup {
        if (CaptureCursor.owns(cursor)) return fromCapture(sessionId, cursor, false, limit) ?: TranscriptLookup.Missing
        val read = delegate.page(sessionId, roots, cursor, limit)
        return if (read == TranscriptLookup.Missing && cursor == null) {
            fromCapture(sessionId, null, false, limit) ?: read
        } else {
            read
        }
    }

    override fun pageBefore(sessionId: String, roots: List<Path>, before: String?, limit: Int): TranscriptLookup {
        if (CaptureCursor.owns(before)) return fromCapture(sessionId, before, true, limit) ?: TranscriptLookup.Missing
        val fromEnd = before == null || before == "end"
        val read = delegate.pageBefore(sessionId, roots, if (before == "end") null else before, limit)
        return if (read == TranscriptLookup.Missing && fromEnd) {
            fromCapture(sessionId, null, true, limit) ?: read
        } else {
            read
        }
    }

    private fun fromCapture(sessionId: String, cursor: String?, backwards: Boolean, limit: Int): TranscriptLookup? {
        if (!validSessionId.matches(sessionId)) return TranscriptLookup.Refused("not a session id")
        if (cursor != null && CaptureCursor.parse(cursor) == null) return TranscriptLookup.Refused(BAD_CURSOR)
        return readCapture(sessionId, cursor, backwards, limit)
    }

    private fun readCapture(sessionId: String, cursor: String?, backwards: Boolean, limit: Int): TranscriptLookup? {
        val sent = Cancellables.runCatchingBestEffort { captured.newest(sessionId) }.getOrNull()
        val unavailable = if (cursor == null) null else TranscriptLookup.Refused(BAD_CURSOR)
        if (sent == null) return unavailable
        return Cancellables.runCatchingBestEffort { pages.read(sent, sessionId, cursor, backwards, limit) }
            .getOrNull() ?: unavailable
    }
}
