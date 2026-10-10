// NEW: LAYOUT-01 — the transcript contract owned by the sessions feature. The Claude Code integration
// implements it; this module owns only the query and response vocabulary its HTTP projections need.
package splice.sessions.transcript

import java.nio.file.Path

// why: a transcript page the console renders without pagination controls.
internal const val DEFAULT_TRANSCRIPT_PAGE: Int = 100

// why: the ceiling a caller may ask for. It exists so one request cannot ask the daemon to read
// and hold an entire multi-thousand-turn transcript.
public const val MAX_TRANSCRIPT_PAGE: Int = 500

// why: request detail must end a slow whole-conversation read and leave the session's paged view available.
public const val CONVERSATION_READ_TIMEOUT_MS: Long = 10_000L

public const val CONVERSATION_READ_UNAVAILABLE: String =
    "Reading this conversation took too long. Open the session to read its transcript in pages."

/** The skipped-record kinds the page publishes as their own counts, apart from the per-kind map. */
public const val SKIPPED_UNPARSEABLE: String = "unparseable"
public const val SKIPPED_SIDECHAIN: String = "sidechain"

/** PEER is a message another session sent: told to this one, not said by the person at it. */
public enum class TranscriptRole { USER, ASSISTANT, SYSTEM, TOOL, PEER }

/** The kind of a system line that has a name of its own: the person stopped the turn or refused a tool call. */
public const val KIND_INTERRUPTED: String = "interrupted"

public data class TranscriptMessage(
    val index: Long,
    val role: TranscriptRole,
    val ts: Long?,
    val text: String,
    val toolUse: TranscriptToolUse = TranscriptToolUse(),
    /** The id Claude Code wrote on an assistant reply. Several lines share it, and the reader merges
     *  them into this message; the perf row joins its response to this id (V4-354). */
    val messageId: String? = null,
    /** What a message is apart from its role, when the page draws it so; empty on the rest. */
    val source: TranscriptSource = TranscriptSource(),
)

public data class TranscriptSource(
    /** What a system line is, when the page draws it apart from other notes: [KIND_INTERRUPTED]. */
    val kind: String? = null,
    /** On a [TranscriptRole.PEER] message, the session that sent it, by the name it goes by. */
    val from: String? = null,
    /** On an assistant message, the model that wrote it, as the client recorded it. */
    val model: String? = null,
)

/** The tool facts a message carries; every field is null on a message that is neither a call nor a result. */
public data class TranscriptToolUse(
    /** The tool's name: on a call it is the tool called, on a result the tool the result answers when that is known. */
    val name: String? = null,
    /** True on a tool result, false on the call; null on everything else. */
    val result: Boolean? = null,
    /** The client's tool-use id, carried on both the call and its result across page boundaries. */
    val id: String? = null,
)

public data class TranscriptPage(
    val sessionId: String,
    val path: String,
    val messages: List<TranscriptMessage>,
    val next: String?,
    /** Records this page read past, by kind, [SKIPPED_UNPARSEABLE] and [SKIPPED_SIDECHAIN] included. */
    val skipped: Map<String, Int>,
    /** Set on a page read from the end of the file ([SessionTranscripts.pageBefore]): the cursor of the page before it,
     *  null at the start of the file. [next] is null on those pages. */
    val earlier: String? = null,
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

    /** The source could not complete this read; it does not claim the reply is absent. */
    public data class Unavailable(val reason: String) : MessageConversation()

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

/** The request's remaining read budget, shared across every root and every indexing chunk. */
public fun interface TranscriptReadBudget {
    public fun hasTime(): Boolean
}

/** Reads Claude Code transcript data from [roots], in caller-defined priority order. */
public interface SessionTranscripts {
    /** The newest redacted main-thread message that is not a tool result or a system note, read from a bounded
     *  tail and cached by file stamp: what the session last said, was told, or called. Indices are local to that
     *  tail, not page cursors. Null when no complete message is available; older port implementations provide no
     *  activity. [cwd] is the session's working directory when the caller knows it, which is where Claude Code files
     *  the transcript: an implementation may look there first and judge a miss against it (V4-444). */
    public fun last(sessionId: String, roots: List<Path>, cwd: String? = null): TranscriptMessage? = null

    /** The model that wrote the newest assistant message in the same bounded tail, whatever the last message is: a
     *  session waiting on its person ends on a user message and still has one. Null when none is recorded there. */
    public fun model(sessionId: String, roots: List<Path>, cwd: String? = null): String? = null

    public fun page(
        sessionId: String,
        roots: List<Path>,
        cursor: String?,
        limit: Int,
    ): TranscriptLookup

    /** The newest [limit] messages at or before the cursor [before] (null: the end of the file), oldest first, with
     *  [TranscriptPage.earlier] naming the page before them. A message's indices rise with its place in the file and
     *  never repeat across pages, but they are not the forward pages' counts. Ports that only page forward refuse. */
    public fun pageBefore(
        sessionId: String,
        roots: List<Path>,
        before: String?,
        limit: Int,
    ): TranscriptLookup = TranscriptLookup.Refused("this transcript source pages forward only")

    /** An indexed response and at most [context] earlier messages. Null means this port does not support indexed
     *  lookup; the caller may use its forward pages. A supported port reports absence or a spent [budget] explicitly. */
    public fun response(
        sessionId: String,
        roots: List<Path>,
        responseId: String,
        context: Int,
        budget: TranscriptReadBudget,
    ): MessageConversation? = null

    public fun sentTexts(
        sessionId: String,
        roots: List<Path>,
        ids: Set<String>,
    ): SentTexts
}
