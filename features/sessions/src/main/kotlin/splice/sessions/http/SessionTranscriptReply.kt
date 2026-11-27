package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.http.JsonReply
import splice.sessions.registry.SessionRecord
import splice.sessions.transcript.DEFAULT_TRANSCRIPT_PAGE
import splice.sessions.transcript.ModelMove
import splice.sessions.transcript.SKIPPED_UNPARSEABLE
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptPage
import java.nio.file.Path

/** One transcript message as the route sends it. */
private object MessageWire {
    fun json(m: TranscriptMessage): JsonObject = buildJsonObject {
        put("index", m.index)
        put("role", m.role.name.lowercase())
        m.ts?.let { put("ts", it) }
        put("text", m.text)
        m.toolUse.name?.let { put("tool", it) }
        m.toolUse.id?.let { put("tool_use_id", it) }
        m.source.kind?.let { put("kind", it) }
        m.source.from?.let { put("from", it) }
        reply(this, m)
    }

    /** The reply a message belongs to (the id the live feed's `turn` event names, so a reader can settle a live reply
     *  into it), the model that wrote it and its thinking, and the mark of a compaction's summary. */
    private fun reply(into: JsonObjectBuilder, m: TranscriptMessage) {
        m.messageId?.let { into.put("message", it) }
        m.source.model?.let { into.put("model", it) }
        m.source.thinking?.let { into.put("thinking", it) }
        if (m.source.compact) into.put("compact", true)
    }
}

private object MoveWire {
    fun json(move: ModelMove): JsonObject = buildJsonObject {
        put("model", move.model)
        move.command?.let { put("command", it) }
        put("moved_at", move.movedAt)
        move.fromCommand?.let { put("from_command", it) }
        move.fromModel?.let { put("from_model", it) }
        put("thinking_dropped", move.thinkingDropped)
    }
}

/** `before=end` on the transcript route: read from the last message. */
private const val FROM_END = "end"

// why: before=at:<epoch ms> opens the page ending at that moment: a request's door to its place in a conversation.
private const val AT_MOMENT = "at:"

// why: cursor=after:<index> reads from where the message a cursor `after:<index>` names. A live pane's
// five-second poll asks only for its last message, which may have grown, and what came after it, a few KB, where a read
// from the end sent its whole page again.
private const val AFTER_MESSAGE = "after:"

/** One transcript read: forward from [cursor], back from [before] (or the end), the page ending at `at:<ms>`, or what
 *  follows the message a cursor `after:<index>` names. [cwd] is the session's working directory as its row reads it,
 *  so a session with no transcript yet is judged missing where its row judged it, without a walk on every read. */
private class TranscriptAsk(
    val trees: List<Path>,
    val cursor: String?,
    val size: Int,
    val before: String?,
    val cwd: String?,
) {
    /** The message index an `after:` cursor names; null for every other read. */
    val after: Long? = cursor?.takeIf { before == null && it.startsWith(AFTER_MESSAGE) }
        ?.removePrefix(AFTER_MESSAGE)?.toLongOrNull()?.takeIf { it >= 0 }

    fun lookup(transcripts: SessionTranscripts, sessionId: String): TranscriptLookup = when {
        after != null -> transcripts.pageAfter(sessionId, trees, after, size, cwd)
        cursor?.startsWith(AFTER_MESSAGE) == true -> TranscriptLookup.Refused("not a message this daemon numbered")
        before == null -> transcripts.page(sessionId, trees, cursor, size, cwd)
        before.startsWith(AT_MOMENT) -> before.removePrefix(AT_MOMENT).toLongOrNull()
            ?.let { transcripts.pageAt(sessionId, trees, it, size, cwd) }
            ?: TranscriptLookup.Refused("not a moment this daemon reads")
        else -> transcripts.pageBefore(sessionId, trees, before.takeUnless { it == FROM_END }, size, cwd)
    }
}

/** Owns the transcript page cursor protocol and its HTTP reply, independently of session listings. */
internal class SessionTranscriptReply(private val transcripts: SessionTranscripts, private val roots: TranscriptRoots) {
    fun read(sessionId: String, record: SessionRecord?, cursor: String?, limit: Int?, before: String?): JsonReply {
        val ask = TranscriptAsk(
            roots.treesFor(record?.head),
            cursor,
            limit ?: DEFAULT_TRANSCRIPT_PAGE,
            before,
            record?.process?.cwd,
        )
        return when (val lookup = ask.lookup(transcripts, sessionId)) {
            is TranscriptLookup.Found ->
                JsonReply(HttpStatusCode.OK, pageJson(lookup.page, before != null, ask.after != null))
            TranscriptLookup.Missing -> JsonReply(
                HttpStatusCode.NotFound,
                buildJsonObject { put("error", "no transcript for this session id") }.toString(),
            )
            is TranscriptLookup.Refused -> JsonReply(
                HttpStatusCode.BadRequest,
                buildJsonObject { put("error", lookup.reason) }.toString(),
            )
        }
    }

    private fun pageJson(page: TranscriptPage, fromEnd: Boolean, after: Boolean): String = buildJsonObject {
        put("messages", buildJsonArray { page.messages.forEach { add(MessageWire.json(it)) } })
        put("path", page.path) // the transcript file this page was read from, which the pane names under its log
        if (fromEnd) put("earlier", page.earlier)
        // more came after the message than one page holds: the pane reads from the end instead
        if (after) put("more", page.next != null)
        put("moves", buildJsonArray { transcripts.moves(page.sessionId).forEach { add(MoveWire.json(it)) } })
        // a move that did not come back leaves no mark, but splice ended the client for it: when he pressed
        put("unmoved", buildJsonArray { transcripts.unmoved(page.sessionId).forEach { add(JsonPrimitive(it)) } })
        // the lines this page could not read: Sessions counts them into what it says it could not show
        put("unparseable_lines", page.skipped[SKIPPED_UNPARSEABLE] ?: 0)
    }.toString()
}
