// NEW: V4-130, FEATURES.md 4.4 and 6 — a session's own transcript, read as a conversation, one page at
// a time, for GET /api/sessions/{id}/transcript.
//
// WHERE IT READS. Claude Code writes `<config dir>/projects/<slug>/<session id>.jsonl`. The caller
// supplies the config roots in priority order (SessionTranscripts' `roots`): the session's head config dir, then
// the vanilla ~/.claude tree (sessions splice did not launch, and history from before V4-115
// un-linked the trees), then every other head's. The page reports the path it opened.
//
// THE TREES ARE A MIX OF OWN DIRECTORIES AND SYMLINKS, measured by the orchestrator on 2026-09-18
// across every ~/.claude* root: nine heads keep an OWN projects tree (codex alone holds 1544
// transcripts there) and four heads' projects dir is a SYMLINK to ~/.claude/projects. So one
// directory can appear under five root names; each projects dir is resolved to its real path and a
// repeat is dropped BEFORE it is walked, which makes a double count impossible rather than corrected
// afterwards. THE PATH REPORTED IS THE NAME THE WINNING ROOT GAVE IT: for a symlinked head it may read
// ~/.claude-<head>/projects/... while the file lives under ~/.claude/projects; both name one file.
//
// WHICH COPY WINS when a session id exists in two own trees (a session adopted across heads with
// -r keeps a copy in each): the first in the caller's order, which puts the session's OWN head tree
// first, because that is the copy its live client is still appending to. The later roots are the
// common case, not a defensive branch: a session whose head the registry does not name (a gone pid
// loses its head) starts at the vanilla tree and is found in one of the nine own trees behind it
// (TranscriptReaderTest builds that case). Nothing is ever written into any of these trees.
//
// ONE LINE IS NOT ONE MESSAGE. The client writes one line per content block and several lines share
// one `message.id` (a thinking block, then the text, then each tool call); they are merged back into
// the one API message they came from, so nothing is double-counted or reordered. A page never ends
// inside a message.
//
// MOST RECORDS ARE NOT MESSAGES. Measured on a 33,604-line transcript: 20 record kinds, among them
// attachment, queue-operation, file-history-snapshot, last-prompt, custom-title, agent-name, mode,
// permission-mode, atis-latch, bridge-session and system records with no text. `.message` is never
// assumed: a record that is not part of the conversation is SKIPPED AND COUNTED by kind, and the page
// publishes the counts — the denominator of what it shows.
//
// SUBAGENT TURNS ARE EXCLUDED. A record with `isSidechain: true` is a subagent's own conversation, not
// this session's; the view is the session's main thread, and the excluded records are counted.
//
// A LINE THAT DOES NOT PARSE IS COUNTED, NEVER FATAL. Three transcripts on the operator's machine
// carry pre-existing unparseable lines; a reader that threw would lose the whole session.
//
// API ERRORS ARE SURFACED. An `isApiErrorMessage` record is how a session's own failure sits in its
// transcript in plain text; it is emitted as a system message prefixed "API error:" rather than
// hidden among assistant turns, because it is the line an operator is looking for.
//
// NEVER THE WHOLE FILE. The largest transcript measured was 118,000 lines and 95 MB. A page reads
// forward from a byte offset carried in its cursor and stops at its message limit or MAX_PAGE_BYTES
// read, whichever comes first. The cursor is `<byte offset>.<next message index>`, opaque to the
// console. No total is reported: counting would mean reading the file.
//
// REDACTED. Credential shapes (bearer and JWT tokens, key=value secrets, provider keys) are masked in
// every text. Paths and ids are kept: this is the operator's own conversation, shown to them.
//
// V4-131: SENT TEXTS, for a team's chat (GET /api/teams/{id}/chat). The message edge store holds who
// wrote to whom and the call's tool_use id, never the text (MessageEdges); the text is read here, on
// demand, from the SENDER's transcript, found by the same locate the page uses so the tree order and
// the realpath dedupe have one copy. V4-427 holds every redacted send per file in SentTextLedger:
// unchanged size/mtime reads zero bytes; growth resumes at the last whole line after checking the
// old EOF; replacement, shrink or same-size rewrite scans from the start. Only SendMessage lines
// are parsed. An id it does not find is REPORTED missing with the path it read: a lookup that
// silently answered fewer ids than it was asked for would read as a complete chat.
//
// V4-444: card activity reads backwards in positioned 64 KiB chunks, up to 16 MiB for image results.
// The same parser and PageAssembly merge the last main-thread reply; file stamps cache the result.
// No complete message within that bounded tail means no activity can be shown from it.
//
// 2026-09-18 (V4-160, concentration): page assembly moved to TranscriptAssembly.kt and redaction to
// TranscriptRedaction.kt. LAYOUT-01 later moved the public response vocabulary to :features-sessions.
package splice.client.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.client.Keys
import splice.core.util.JsonScalars
import splice.sessions.transcript.CONVERSATION_READ_UNAVAILABLE
import splice.sessions.transcript.MAX_TRANSCRIPT_PAGE
import splice.sessions.transcript.MessageConversation
import splice.sessions.transcript.SentTexts
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptPage
import splice.sessions.transcript.TranscriptReadBudget
import splice.sessions.transcript.TranscriptRole
import java.io.IOException
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Bytes one page may read before it stops, whatever it found: a run of skipped records must not
 *  turn one page into a full-file read. */
private const val MAX_PAGE_BYTES = 16L shl 20

private const val BAD_ID = "not a session id"
private const val SEND_MESSAGE = "SendMessage"
internal const val BAD_CURSOR = "not a cursor this daemon minted"

/** The Claude Code implementation of the sessions feature's transcript port: the feature chooses root
 *  priority, this class owns only Claude Code's on-disk format. */
public class TranscriptReader(
    private val opener: TranscriptOpener = TranscriptOpener { file, offset ->
        Channels.newInputStream(Files.newByteChannel(file, StandardOpenOption.READ).position(offset))
    },
) : SessionTranscripts {
    private val locator = TranscriptLocator()
    private val replies = TranscriptResponseIndex(opener)

    private val sent = SentTextLedger(opener, SentTextCollector(::collect))

    private val json = Json { ignoreUnknownKeys = true }
    private val validSessionId = Regex("[A-Za-z0-9_-]{1,128}")
    private val redaction = TranscriptRedaction()
    private val tail = TranscriptTail(opener, TranscriptTailAssembly(TranscriptLineParser(::parse), redaction))
    private val back = TranscriptBackPage(opener, TranscriptLineParser(::parse), redaction)

    override fun last(sessionId: String, roots: List<Path>, cwd: String?): TranscriptMessage? =
        tailOf(sessionId, roots, cwd)?.message

    override fun model(sessionId: String, roots: List<Path>, cwd: String?): String? =
        tailOf(sessionId, roots, cwd)?.model

    private fun tailOf(sessionId: String, roots: List<Path>, cwd: String?): TailReading? {
        val file = if (validSessionId.matches(sessionId)) locate(roots, sessionId, cwd) else null
        if (file == null) return null
        // A removed or unreadable transcript has no available activity; the registry row still exists independently.
        return try {
            tail.read(file)
        } catch (_: IOException) {
            null
        }
    }

    override fun page(sessionId: String, roots: List<Path>, cursor: String?, limit: Int): TranscriptLookup {
        val start = parseCursor(cursor)
        if (!validSessionId.matches(sessionId) || start == null) {
            return TranscriptLookup.Refused(if (start == null) BAD_CURSOR else BAD_ID)
        }
        val file = locate(roots, sessionId)
            ?: return TranscriptLookup.Missing(roots.map { it.resolve(Keys.PROJECTS).toString() })
        return TranscriptLookup.Found(read(file, sessionId, start, limit.coerceIn(1, MAX_TRANSCRIPT_PAGE)))
    }

    override fun pageBefore(sessionId: String, roots: List<Path>, before: String?, limit: Int): TranscriptLookup {
        val end = before?.toLongOrNull()?.takeIf { it >= 0 }
        // Refused before any file is looked for: a cursor that is no offset, or an id that is no id.
        val refusal = when {
            before != null && end == null -> BAD_CURSOR
            !validSessionId.matches(sessionId) -> BAD_ID
            else -> null
        }
        val file = if (refusal == null) locate(roots, sessionId) else null
        return when {
            refusal != null -> TranscriptLookup.Refused(refusal)
            file == null -> TranscriptLookup.Missing(roots.map { it.resolve(Keys.PROJECTS).toString() })
            else -> back.lookup(file, sessionId, end, limit)
        }
    }

    override fun response(
        sessionId: String,
        roots: List<Path>,
        responseId: String,
        context: Int,
        budget: TranscriptReadBudget,
    ): MessageConversation {
        if (!validSessionId.matches(sessionId)) return MessageConversation.Refused(BAD_ID)
        val file = locate(roots, sessionId)
            ?: return MessageConversation.Missing("No saved transcript for this session.")
        return when (val indexed = replies.find(file, responseId, budget)) {
            is IndexedReply.Found -> if (budget.hasTime()) {
                val point = indexed.point
                val page = back.read(file, sessionId, point.end, context + point.messages)
                selected(page, responseId, point, context)
            } else {
                MessageConversation.Unavailable(CONVERSATION_READ_UNAVAILABLE)
            }
            IndexedReply.Missing -> MessageConversation.Missing("No matching reply in this session's saved transcript.")
            IndexedReply.BudgetSpent -> MessageConversation.Unavailable(CONVERSATION_READ_UNAVAILABLE)
        }
    }

    private fun selected(
        page: TranscriptPage,
        responseId: String,
        point: TranscriptReplyPoint,
        context: Int,
    ): MessageConversation {
        val at = page.messages.indexOfFirst { it.role == TranscriptRole.ASSISTANT && it.messageId == responseId }
        if (at < 0) return MessageConversation.Unavailable(CONVERSATION_READ_UNAVAILABLE)
        val reply = page.messages.drop(at).takeWhile {
            it.role == TranscriptRole.ASSISTANT && it.messageId == responseId
        }
        val complete = reply.size == point.messages && reply.first().index / PER_RECORD == point.start
        if (!complete) return MessageConversation.Unavailable(CONVERSATION_READ_UNAVAILABLE)
        val before = page.messages.take(at).takeLast(context)
        return MessageConversation.Found(
            page.sessionId,
            responseId,
            before + TranscriptReplyMerger().merge(reply),
            point.before - before.size,
        )
    }

    /** The `message` of every SendMessage call in [sessionId]'s transcript whose tool_use id is in
     *  [ids] (see the header). A malformed session id finds nothing and reports every id missing. */
    override fun sentTexts(sessionId: String, roots: List<Path>, ids: Set<String>): SentTexts {
        val file = if (validSessionId.matches(sessionId)) locate(roots, sessionId) else null
        if (file == null) return SentTexts(null, emptyMap(), ids, roots.map { it.resolve(Keys.PROJECTS).toString() })
        return sent.read(file, ids)
    }

    /** Only SendMessage candidates are parsed. All redacted sends are held, regardless of this caller's ids. */
    private fun collect(bytes: ByteArray, found: MutableMap<String, String>) {
        val text = bytes.toString(Charsets.UTF_8)
        if (!text.contains(SEND_MESSAGE) && !text.contains("\\u")) return
        val record = parse(bytes) ?: return
        sends(record).forEach { (id, text) -> found.putIfAbsent(id, redaction.shown(text)) }
    }

    /** The SendMessage calls of one assistant record, id to message. A `message` that is not
     *  a string (a structured request) is shown as its JSON. */
    private fun sends(record: JsonObject): List<Pair<String, String>> {
        val message = record[MESSAGE] as? JsonObject
        val blocks = (message?.get("content") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        return blocks
            .filter { JsonScalars.str(it, "type") == "tool_use" && JsonScalars.str(it, "name") == SEND_MESSAGE }
            .mapNotNull { block -> JsonScalars.str(block, "id")?.let { it to block } }
            .map { (id, block) ->
                val sent = (block["input"] as? JsonObject)?.get(MESSAGE)
                id to ((sent as? JsonPrimitive)?.takeIf { it.isString }?.content ?: sent?.toString().orEmpty())
            }
    }

    private fun locate(roots: List<Path>, sessionId: String, cwd: String? = null): Path? =
        locator.locate(roots, sessionId, cwd)

    /** Lines from [start] until the page is full, the byte budget is spent or the file ends; a line the
     *  assembly declines (it starts the next page's first message) is left unread for that page. */
    private fun read(file: Path, sessionId: String, start: Position, limit: Int): TranscriptPage {
        val assembly = PageAssembly(start.index, limit, redaction)
        val size = Files.size(file)
        val from = start.offset.coerceAtMost(size)
        var offset = from
        opener.open(file, from).use { input ->
            val lines = TranscriptLineReader(input, from, size)
            var more = true
            while (more && offset - from < MAX_PAGE_BYTES) {
                val taken = lines.next()?.takeIf { assembly.accept(it.bytes?.let(::parse)) }
                more = taken != null
                offset = taken?.next ?: offset
            }
        }
        val messages = assembly.finish()
        return TranscriptPage(
            sessionId = sessionId,
            path = file.toString(),
            messages = messages,
            next = if (offset < size) "$offset.${assembly.nextIndex}" else null,
            skipped = assembly.skipped,
        )
    }

    private fun parse(bytes: ByteArray): JsonObject? =
        // An unparseable line is COUNTED by PageAssembly (skipped["unparseable"]), never fatal: one bad line must not
        // lose the session.
        try {
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        } catch (_: IllegalArgumentException) {
            null
        }

    /** `<offset>.<index>`, both non-negative; "1.2.3" and "-1.0" are refused, never guessed at. */
    private fun parseCursor(cursor: String?): Position? {
        if (cursor == null) return Position(0L, 0L)
        val offset = cursor.substringBefore('.', "").toLongOrNull()?.takeIf { it >= 0 }
        val index = cursor.substringAfter('.', "").toLongOrNull()?.takeIf { it >= 0 }
        return if (offset != null && index != null) Position(offset, index) else null
    }

    private data class Position(val offset: Long, val index: Long)
}
