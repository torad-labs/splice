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
) : SessionTurnEnded {
    private val spoken = setOf(TranscriptRole.USER, TranscriptRole.ASSISTANT)

    override fun untouched(record: SessionRecord, endedAtMs: Long): Boolean {
        // reading off, splice opens no conversation: the perf row's ending and the console's own marks decide alone
        return if (!config.getConfig().transcriptView) {
            true
        } else {
            record.sessionId?.let { id ->
                messages(record, id)?.let { ended(it, endedAtMs) }
            } ?: false
        }
    }

    private fun messages(record: SessionRecord, id: String): List<TranscriptMessage>? {
        val page = transcripts.pageBefore(id, roots.treesFor(record.head), null, LIMIT_TAIL, record.process.cwd)
        return (page as? TranscriptLookup.Found)?.page?.messages
    }

    private fun ended(messages: List<TranscriptMessage>, endedAtMs: Long): Boolean {
        val anchor = anchor(messages, endedAtMs)
        return messages.withIndex().none { (i, message) -> spokenAfter(message, i, anchor) }
    }

    private data class EndingAnchor(val cutoff: Long, val errorIndex: Int?)

    private fun anchor(messages: List<TranscriptMessage>, endedAtMs: Long): EndingAnchor {
        val at = messages.indexOfLast { it.role == TranscriptRole.SYSTEM && it.text.startsWith(LIMIT_ERROR, true) }
        val errorAt = messages.getOrNull(at)?.ts
        val relevantError = at >= 0 && current(errorAt, endedAtMs)
        val cutoff = if (relevantError && errorAt != null) errorAt else endedAtMs
        return EndingAnchor(cutoff, at.takeIf { relevantError })
    }

    private fun current(errorAt: Long?, endedAtMs: Long): Boolean = errorAt == null || errorAt >= endedAtMs

    private fun spokenAfter(message: TranscriptMessage, index: Int, anchor: EndingAnchor): Boolean =
        message.role in spoken &&
            (message.ts?.let { it > anchor.cutoff } ?: anchor.errorIndex?.let { index > it } ?: true)
}
