// NEW: Oct 12, 2026 — whether a session's turn ended at its plan limit, read from its own conversation: LimitResume's
// LimitTurnEnded over the transcript reader. Moved out of SessionsWiring, which is wiring (push of 086fdebfb: the
// concentration band HIGH).
package splice.app.control

import splice.core.config.ConfigService
import splice.sessions.http.TranscriptRoots
import splice.sessions.registry.SessionRecord
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptRole

// why: Claude Code writes a turn's limit as its own API error record, which the reader pages as a system message
// opening with these words; the perf row already says the ending was the limit.
private const val LIMIT_ERROR = "API error"

// why: the limit's error, the client's own "continuing automatically" line and the prompt it writes back after them
// all sit in a turn's last few messages; this many covers them with room.
private const val LIMIT_TAIL = 8

/** A conversation ends at the limit when its newest messages hold the limit's error with nothing he or the model said
 *  after it. Claude Code 2.1.296 writes the stopped prompt back AFTER the error in the file but stamped before it
 *  (limit walk, Oct 12, 6:24 PM CT), so "after" is by time; a message with no time is ordered by its place. */
internal class TranscriptLimitEnding(
    private val config: ConfigService,
    private val transcripts: SessionTranscripts,
    private val roots: TranscriptRoots,
) : LimitTurnEnded {
    private val spoken = setOf(TranscriptRole.USER, TranscriptRole.ASSISTANT)

    override fun endedAtLimit(record: SessionRecord): Boolean {
        // reading off, splice opens no conversation: the perf row's ending and the console's own marks decide alone
        if (!config.getConfig().transcriptView) return true
        val id = record.sessionId ?: return false
        val page = transcripts.pageBefore(id, roots.treesFor(record.head), null, LIMIT_TAIL, record.process.cwd)
        return ended((page as? TranscriptLookup.Found)?.page?.messages.orEmpty())
    }

    private fun ended(messages: List<TranscriptMessage>): Boolean {
        val at = messages.indexOfLast { it.role == TranscriptRole.SYSTEM && it.text.startsWith(LIMIT_ERROR, true) }
        if (at < 0) return false
        val error = messages[at]
        return messages.withIndex().none { (i, m) -> m.role in spoken && later(m, i, error, at) }
    }

    private fun later(m: TranscriptMessage, i: Int, error: TranscriptMessage, at: Int): Boolean {
        val mine = m.ts
        val its = error.ts
        return if (mine != null && its != null) mine > its else i > at
    }
}
