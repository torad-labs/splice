// NEW: V4-354 — select one reply in Claude Code's OWN local transcript by the message id splice put on
// the wire. Reuse TranscriptReader's page and PageAssembly: the reader already merges every line
// with that message.id, applies credential redaction, and bounds each read's bytes. Never re-parse
// the JSONL here or open an untrusted path from a request. The session id and root priority come
// from the caller; this adapter returns only conversation text, never headers or raw request bytes.
package splice.client.transcript

import splice.sessions.transcript.MAX_TRANSCRIPT_PAGE
import splice.sessions.transcript.MessageConversation
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptMessageSource
import splice.sessions.transcript.TranscriptRole
import java.nio.file.Path

// why: a selected response's latest context stays bounded even in a transcript with 118,000 lines;
// the Sessions page keeps the earlier pages available under the same session id.
private const val CONTEXT_MESSAGES = 100

/** The id format is a bounded lookup value, never a filesystem path or a pattern. */
private val RESPONSE_ID = Regex("[A-Za-z0-9_-]{1,128}")

/** Claude Code's redacted conversation through one client-facing assistant response. */
public class TranscriptMessageLookup(
    private val pages: SessionTranscripts = TranscriptReader(),
) : TranscriptMessageSource {
    override fun lookup(sessionId: String, roots: List<Path>, responseId: String): MessageConversation {
        return if (RESPONSE_ID.matches(responseId)) {
            search(sessionId, roots, responseId)
        } else {
            MessageConversation.Refused("not a response message id")
        }
    }

    /** One session id can exist in more than one own tree after a head hand-off. The preferred copy
     *  wins only if it HOLDS this response id; a copy ending before it cannot hide a later one. */
    private fun search(sessionId: String, roots: List<Path>, responseId: String): MessageConversation {
        for (root in roots) {
            val answer = scan(sessionId, listOf(root), responseId)
            if (answer !is MessageConversation.Missing) return answer
        }
        return MessageConversation.Missing("No saved reply for this session; its transcript may be absent or pruned.")
    }

    private class ScanState {
        var cursor: String? = null
        val before = ArrayDeque<TranscriptMessage>()
        val reply = mutableListOf<TranscriptMessage>()
        var seen = 0L
    }

    private fun scan(sessionId: String, roots: List<Path>, responseId: String): MessageConversation {
        val state = ScanState()
        var answer: MessageConversation? = null
        while (answer == null) {
            answer = when (val read = pages.page(sessionId, roots, state.cursor, MAX_TRANSCRIPT_PAGE)) {
                is TranscriptLookup.Missing -> MessageConversation.Missing("No saved transcript for this session.")
                is TranscriptLookup.Refused -> MessageConversation.Refused(read.reason)
                is TranscriptLookup.Found -> {
                    val after = scanPage(read.page.messages, responseId, state)
                    val decided = decide(state, read.page.next, after, sessionId, responseId)
                    if (decided == null) state.cursor = read.page.next
                    decided
                }
            }
        }
        return answer
    }

    /** An assistant response can cross the reader's byte boundary. Keep reading until its next
     *  distinct message, not merely until its first page yielded a fragment of this id. */
    private fun decide(
        state: ScanState,
        next: String?,
        after: Boolean,
        sessionId: String,
        responseId: String,
    ): MessageConversation? {
        val responseComplete = after || next == null
        return when {
            state.reply.isNotEmpty() && responseComplete -> MessageConversation.Found(
                sessionId,
                responseId,
                state.before.toList() + mergeReply(state.reply),
                state.seen - state.before.size,
            )
            next == null -> MessageConversation.Missing("No matching reply in this session's saved transcript.")
            next == state.cursor -> MessageConversation.Refused("the saved transcript did not advance")
            else -> null
        }
    }

    private fun scanPage(messages: List<TranscriptMessage>, responseId: String, state: ScanState): Boolean {
        var after = false
        for (message in messages) {
            val sameReply = message.role == TranscriptRole.ASSISTANT && message.messageId == responseId
            if (!after && sameReply) {
                state.reply += message
            } else if (state.reply.isEmpty()) {
                state.seen += 1
                if (state.before.size == CONTEXT_MESSAGES) state.before.removeFirst()
                state.before.addLast(message)
            } else {
                after = true
            }
        }
        return after
    }

    private fun mergeReply(parts: List<TranscriptMessage>): List<TranscriptMessage> {
        val merged = mutableListOf<TranscriptMessage>()
        for (part in parts) {
            val prior = merged.lastOrNull()
            val bothText = prior?.tool == null && part.tool == null
            val join = prior != null && bothText && prior.messageId == part.messageId
            if (join) {
                val previous = checkNotNull(prior)
                merged[merged.lastIndex] = previous.copy(text = previous.text + "\n\n" + part.text)
            } else {
                merged += part
            }
        }
        return merged
    }
}
