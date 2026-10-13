// NEW: LAYOUT-01 — the transcript contract owned by the sessions feature. The Claude Code integration
// implements it; this module owns only the query and response vocabulary its HTTP projections need.
package splice.sessions.transcript

import splice.sessions.registry.SessionRecord
import java.nio.file.Path

// why: a transcript page the console renders without pagination controls.
internal const val DEFAULT_TRANSCRIPT_PAGE: Int = 100

// why: the ceiling a caller may ask for. It exists so one request cannot ask the daemon to read
// and hold an entire multi-thousand-turn transcript.
public const val MAX_TRANSCRIPT_PAGE: Int = 500

/** The skipped-record kinds the page publishes as their own counts, apart from the per-kind map. */
public const val SKIPPED_UNPARSEABLE: String = "unparseable"
public const val SKIPPED_SIDECHAIN: String = "sidechain"

/** PEER is a message another session sent: told to this one, not said by the person at it. */
public enum class TranscriptRole { USER, ASSISTANT, SYSTEM, TOOL, PEER }

/** The kind of a system line that has a name of its own: the person stopped the turn or refused a tool call. */
public const val KIND_INTERRUPTED: String = "interrupted"

/** The kind of a message of the person's that Claude Code took back into the prompt: a Stop landed before any reply. */
public const val KIND_TAKEN_BACK: String = "taken_back"

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

/** [model] is null when none is recorded; [answered] is the newest message that is not the person's, null when there is none.
 *  [modelAt] is when the reply that named [model] was written, which is what a later move is newer than. */
public data class SessionTail(
    val model: String?,
    val answered: TranscriptMessage?,
    val modelAt: Long? = null,
)

/** A session's move onto another model: the message it moved at (the first moved assistant message; null when no row
 *  changed model, as on a move between two commands that serve it), the model it went on, the command (the head's
 *  key) it went on when known, and when, in epoch milliseconds. A move made with Continue on also names the command
 *  and the model it left; [thinkingDropped] is true when moved rows lost their thinking, the one thing not carried. */
public data class ModelMove(
    val messageId: String?,
    val model: String,
    val command: String?,
    val movedAt: Long,
    val fromCommand: String? = null,
    val fromModel: String? = null,
    val thinkingDropped: Boolean = false,
)

public data class TranscriptSource(
    /** What a system line is, when the page draws it apart from other notes: [KIND_INTERRUPTED], or a person's message that was taken back, [KIND_TAKEN_BACK]. */
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

    /** No root holds a transcript for this session id. */
    public data object Missing : TranscriptLookup()

    /** The request itself cannot be served: a malformed id or cursor. */
    public data class Refused(val reason: String) : TranscriptLookup()
}

/** What [SessionTranscripts.sentTexts] found. */
public data class SentTexts(
    val path: String?,
    val texts: Map<String, String>,
    val missing: Set<String>,
    val searched: List<String> = emptyList(),
)

/** What a session with no name of its own is called: the first line of its first message, or null when it has a name, the
 *  view is off, or no message of the person's is readable. The one answer session rows and request rows share. */
public fun interface SessionTitleOf {
    public operator fun invoke(record: SessionRecord): String?
}

/** Reads Claude Code transcript data from [roots], in caller-defined priority order. */
public interface SessionTranscripts {
    /** The newest redacted main-thread message that is not a tool result or a system note, read from a bounded
     *  tail and cached by file stamp: what the session last said, was told, or called. Indices are local to that
     *  tail, not page cursors. Null when no complete message is available; older port implementations provide no
     *  activity. [cwd] is the session's working directory when the caller knows it, which is where Claude Code files
     *  the transcript: an implementation may look there first and judge a miss against it (V4-444). */
    public fun last(sessionId: String, roots: List<Path>, cwd: String? = null): TranscriptMessage? = null

    /** What the same bounded tail says besides its last message: the model of the newest assistant message, whatever the
     *  last message is (a session waiting on its person ends on a user message and still has one), and the last message
     *  that is not the person's, for a session that is not running, whose newest message of his was taken back or never
     *  answered. Null when the transcript is not available. */
    public fun tail(sessionId: String, roots: List<Path>, cwd: String? = null): SessionTail? = null

    /** The first line of the first real message the person sent in [sessionId], cut to a card heading and cached by file
     *  stamp: what a session with no name of its own is called. Null when no message of the person's is readable. */
    public fun title(sessionId: String, roots: List<Path>, cwd: String? = null): String? = null

    /** Where [sessionId] changed model, oldest first; none for a session that was never moved. */
    public fun moves(sessionId: String): List<ModelMove> = emptyList()

    /** When [sessionId]'s moves that did not come back were pressed, oldest first: the client was ended for each. */
    public fun unmoved(sessionId: String): List<Long> = emptyList()

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

    /** The newest [limit] messages written at or before [atMs], as [pageBefore] reads them: a door from a request opens
     *  its agent at that request's place, however deep in the file it lies. Ports that cannot seek refuse. */
    public fun pageAt(
        sessionId: String,
        roots: List<Path>,
        atMs: Long,
        limit: Int,
    ): TranscriptLookup = TranscriptLookup.Refused("this transcript source cannot open at a moment")

    /** Up to [limit] messages from where the message numbered [after] starts, as [pageBefore] numbers them: a live pane
     *  reads its last message again, which may have grown, and what follows it, never the page it already has.
     *  [TranscriptPage.next] is set when more follows than [limit]. Ports that cannot seek refuse. */
    public fun pageAfter(
        sessionId: String,
        roots: List<Path>,
        after: Long,
        limit: Int,
    ): TranscriptLookup = TranscriptLookup.Refused("this transcript source cannot read after a message")

    public fun sentTexts(
        sessionId: String,
        roots: List<Path>,
        ids: Set<String>,
    ): SentTexts
}
