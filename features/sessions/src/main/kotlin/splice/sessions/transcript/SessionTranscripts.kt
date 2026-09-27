// NEW: LAYOUT-01 — the transcript contract owned by the sessions feature. The Claude Code integration
// implements it; this module owns only the query and response vocabulary its HTTP projections need.
package splice.sessions.transcript

import java.nio.file.Path

// why: a transcript page the console renders without pagination controls.
internal const val DEFAULT_TRANSCRIPT_PAGE: Int = 100

// why: the ceiling a caller may ask for. It exists so one request cannot ask the daemon to read
// and hold an entire multi-thousand-turn transcript.
public const val MAX_TRANSCRIPT_PAGE: Int = 500

/** The skipped-record kinds the page publishes as their own counts, apart from the per-kind map. */
public const val SKIPPED_UNPARSEABLE: String = "unparseable"
public const val SKIPPED_SIDECHAIN: String = "sidechain"

public enum class TranscriptRole { USER, ASSISTANT, SYSTEM, TOOL }

public data class TranscriptMessage(
    val index: Long,
    val role: TranscriptRole,
    val ts: Long?,
    val text: String,
    val tool: String? = null,
    /** True on a tool result, false on the call; null on everything else. */
    val result: Boolean? = null,
    /** The id Claude Code wrote on an assistant reply. Several lines share it, and the reader merges
     *  them into this message; the perf row joins its response to this id (V4-354). */
    val messageId: String? = null,
)

public data class TranscriptPage(
    val sessionId: String,
    val path: String,
    val messages: List<TranscriptMessage>,
    val next: String?,
    /** Records this page read past, by kind, [SKIPPED_UNPARSEABLE] and [SKIPPED_SIDECHAIN] included. */
    val skipped: Map<String, Int>,
)

public sealed class TranscriptLookup {
    public data class Found(val page: TranscriptPage) : TranscriptLookup()

    /** No root holds a transcript for this session id; [searched] names every projects directory tried. */
    public data class Missing(val searched: List<String>) : TranscriptLookup()

    /** The request itself cannot be served: a malformed id or cursor. */
    public data class Refused(val reason: String) : TranscriptLookup()
}

/** The redacted context of one response id from a client's local transcript, never raw request bytes.
 *  [earlier] counts messages before this bounded window so the console never claims it holds every
 *  message from a very large transcript (V4-354). */
public sealed class MessageConversation {
    public data class Found(
        val sessionId: String,
        val responseId: String,
        val messages: List<TranscriptMessage>,
        val earlier: Long,
    ) : MessageConversation()

    public data class Missing(val reason: String) : MessageConversation()
    public data class Refused(val reason: String) : MessageConversation()
}

/** An exact response-id lookup against Claude Code's own local transcript, in caller-supplied root
 *  priority. The implementation returns only redacted conversation messages, no headers or bodies. */
public fun interface TranscriptMessageSource {
    public fun lookup(sessionId: String, roots: List<Path>, responseId: String): MessageConversation
}

/** What [SessionTranscripts.sentTexts] found. */
public data class SentTexts(
    val path: String?,
    val texts: Map<String, String>,
    val missing: Set<String>,
    val searched: List<String> = emptyList(),
)

/** Reads Claude Code transcript data from [roots], in caller-defined priority order. */
public interface SessionTranscripts {
    public fun page(
        sessionId: String,
        roots: List<Path>,
        cursor: String?,
        limit: Int,
    ): TranscriptLookup

    public fun sentTexts(
        sessionId: String,
        roots: List<Path>,
        ids: Set<String>,
    ): SentTexts
}
