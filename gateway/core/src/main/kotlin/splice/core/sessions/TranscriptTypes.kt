// NEW: V4-160 — the transcript read's public types, moved verbatim out of TranscriptReader.kt
// (concentration, 2026-09-18). TranscriptReader.kt's header states what a page is and how it is read.
package splice.core.sessions

import java.nio.file.Path

public const val DEFAULT_TRANSCRIPT_PAGE: Int = 100
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
    /** True on a tool RESULT, false on the call; null on everything else. */
    val result: Boolean? = null,
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

    /** No root holds a transcript for this session id; [searched] names every projects dir tried. */
    public data class Missing(val searched: List<String>) : TranscriptLookup()

    /** The request itself cannot be served: a malformed id or cursor. */
    public data class Refused(val reason: String) : TranscriptLookup()
}

/** The config roots to search for [head]'s session, in priority order. Supplied by the caller, who
 *  knows the heads. */
public fun interface TranscriptTrees {
    public operator fun invoke(head: String?): List<Path>
}

/** What [TranscriptReader.sentTexts] found. [path] is the transcript read, or null when no root held
 *  one, and then [searched] names every projects dir tried. [texts] maps each found tool_use id to its
 *  SendMessage `message`, redacted and clipped like a page's text; [missing] is every wanted id the
 *  file did not hold. */
public data class SentTexts(
    val path: String?,
    val texts: Map<String, String>,
    val missing: Set<String>,
    val searched: List<String> = emptyList(),
)
