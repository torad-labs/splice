// NEW: V4-130, FEATURES.md 4.4 and 6 — a session's own transcript, read as a conversation, one page at
// a time, for GET /api/sessions/{id}/transcript.
//
// WHERE IT READS. Claude Code writes `<config dir>/projects/<slug>/<session id>.jsonl`. The caller
// supplies the config roots in priority order (TranscriptTrees): the session's head config dir, then
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
// the realpath dedupe have one copy. One forward pass over the file, and a line is parsed only when
// its bytes contain a wanted id that is not found yet, so the pass costs a byte scan, not a parse per
// line. It stops as soon as every wanted id is found. An id it does not find is REPORTED missing with
// the path it read: a lookup that silently answered fewer ids than it was asked for would read as a
// complete chat.
package splice.core.sessions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

public const val DEFAULT_TRANSCRIPT_PAGE: Int = 100
public const val MAX_TRANSCRIPT_PAGE: Int = 500

/** The skipped-record kinds the page publishes as their own counts, apart from the per-kind map. */
public const val SKIPPED_UNPARSEABLE: String = "unparseable"
public const val SKIPPED_SIDECHAIN: String = "sidechain"

/** Bytes one page may read before it stops, whatever it found: a run of skipped records must not
 *  turn one page into a full-file read. */
private const val MAX_PAGE_BYTES = 16L shl 20

/** One line this long is not a conversation record; it is read past, not kept, and counted. */
private const val MAX_LINE_BYTES = 32 shl 20

/** Text kept per message. A tool result can be megabytes; the text says where it was cut. */
private const val MAX_TEXT_CHARS = 64 shl 10

private const val BAD_ID = "not a session id"
private const val SEND_MESSAGE = "SendMessage"
private const val BAD_CURSOR = "not a cursor this daemon minted"

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

public class TranscriptReader(private val trees: TranscriptTrees) {
    private val json = Json { ignoreUnknownKeys = true }
    private val validSessionId = Regex("[A-Za-z0-9_-]{1,128}")
    private val redaction = TranscriptRedaction()

    public fun page(sessionId: String, head: String?, cursor: String?, limit: Int): TranscriptLookup {
        val start = parseCursor(cursor)
        if (!validSessionId.matches(sessionId) || start == null) {
            return TranscriptLookup.Refused(if (start == null) BAD_CURSOR else BAD_ID)
        }
        val roots = trees(head)
        val file = locate(roots, sessionId)
            ?: return TranscriptLookup.Missing(roots.map { it.resolve("projects").toString() })
        return TranscriptLookup.Found(read(file, sessionId, start, limit.coerceIn(1, MAX_TRANSCRIPT_PAGE)))
    }

    /** The `message` of every SendMessage call in [sessionId]'s transcript whose tool_use id is in
     *  [ids] (see the header). A malformed session id finds nothing and reports every id missing. */
    public fun sentTexts(sessionId: String, head: String?, ids: Set<String>): SentTexts {
        val roots = trees(head)
        val file = if (validSessionId.matches(sessionId)) locate(roots, sessionId) else null
        if (file == null) return SentTexts(null, emptyMap(), ids, roots.map { it.resolve("projects").toString() })
        val found = HashMap<String, String>()
        Files.newInputStream(file).use { raw ->
            val input = BufferedInputStream(raw)
            while (found.size < ids.size) {
                val line = nextLine(input) ?: break
                val text = line.bytes?.toString(Charsets.UTF_8) ?: continue
                if (ids.none { it !in found && text.contains(it) }) continue
                val record = parse(line.bytes) ?: continue
                sends(record, ids).forEach { (id, sent) -> found.putIfAbsent(id, redaction.shown(sent)) }
            }
        }
        return SentTexts(file.toString(), found, ids - found.keys)
    }

    /** The wanted SendMessage calls of one assistant record, id to message. A `message` that is not
     *  a string (a structured request) is shown as its JSON. */
    private fun sends(record: JsonObject, ids: Set<String>): List<Pair<String, String>> {
        val message = record["message"] as? JsonObject
        val blocks = (message?.get("content") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        return blocks
            .filter { JsonScalars.str(it, "type") == "tool_use" && JsonScalars.str(it, "name") == SEND_MESSAGE }
            .mapNotNull { block -> JsonScalars.str(block, "id")?.takeIf { it in ids }?.let { it to block } }
            .map { (id, block) ->
                val sent = (block["input"] as? JsonObject)?.get("message")
                id to ((sent as? JsonPrimitive)?.takeIf { it.isString }?.content ?: sent?.toString().orEmpty())
            }
    }

    /** The first candidate in root order. Each projects dir is walked once, however many root names
     *  reach it: the symlinked heads' dirs resolve to the vanilla one and are dropped as repeats. */
    private fun locate(roots: List<Path>, sessionId: String): Path? {
        val walked = HashSet<Path>()
        return roots.asSequence()
            .map { it.resolve("projects") }
            .filter { walked.add(realPath(it)) }
            .flatMap { projectDirs(it).asSequence() }
            .map { it.resolve("$sessionId.jsonl") }
            .firstOrNull { Files.isRegularFile(it) }
    }

    private fun projectDirs(projects: Path): List<Path> =
        // ast-grep-ignore: kt-no-silent-result-collapse -- an absent or unreadable projects tree holds no transcript, which is what Missing reports with every path it searched
        Cancellables.runCatchingCancellable { Files.newDirectoryStream(projects).use { it.filter(Files::isDirectory) } }
            .getOrDefault(emptyList())

    private fun realPath(dir: Path): Path =
        // ast-grep-ignore: kt-no-silent-result-collapse -- a projects dir that does not exist has no real path; its absolute name keys it, and walking it finds nothing
        Cancellables.runCatchingCancellable { dir.toRealPath() }.getOrDefault(dir.toAbsolutePath().normalize())

    private fun read(file: Path, sessionId: String, start: Position, limit: Int): TranscriptPage {
        val assembly = PageAssembly(start.index, limit, redaction)
        val size = Files.size(file)
        val from = start.offset.coerceAtMost(size)
        var offset = from
        Files.newInputStream(file).use { raw ->
            val input = BufferedInputStream(raw)
            input.skipNBytes(from)
            while (offset - from < MAX_PAGE_BYTES) {
                val line = nextLine(input) ?: break
                val record = line.bytes?.let(::parse)
                if (!assembly.accept(record)) break
                offset += line.length
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
        // ast-grep-ignore: kt-no-silent-result-collapse -- an unparseable line is COUNTED by PageAssembly (skipped["unparseable"]), never fatal: one bad line must not lose the session
        Cancellables.runCatchingCancellable { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
            .getOrNull()

    /** `<offset>.<index>`, both non-negative; "1.2.3" and "-1.0" are refused, never guessed at. */
    private fun parseCursor(cursor: String?): Position? {
        if (cursor == null) return Position(0L, 0L)
        val offset = cursor.substringBefore('.', "").toLongOrNull()?.takeIf { it >= 0 }
        val index = cursor.substringAfter('.', "").toLongOrNull()?.takeIf { it >= 0 }
        return if (offset != null && index != null) Position(offset, index) else null
    }

    /** One line's bytes (null when it was longer than MAX_LINE_BYTES and read past), and how many
     *  bytes it occupied including its newline; null at end of file. */
    private fun nextLine(input: InputStream): Line? {
        val buffer = ByteArrayOutputStream()
        var length = 0L
        var overflow = false
        var b = input.read()
        while (b >= 0) {
            length += 1
            if (b == '\n'.code) break
            if (buffer.size() < MAX_LINE_BYTES) buffer.write(b) else overflow = true
            b = input.read()
        }
        return when {
            length == 0L -> null
            overflow -> Line(null, length)
            else -> Line(buffer.toByteArray(), length)
        }
    }

    private data class Position(val offset: Long, val index: Long)

    private class Line(val bytes: ByteArray?, val length: Long)
}

/** Folds records into conversation messages for one page. [accept] answers false when the page is
 *  full AND the record starts a new message, so the record is left for the next page. */
private class PageAssembly(firstIndex: Long, private val limit: Int, private val redaction: TranscriptRedaction) {
    var nextIndex: Long = firstIndex
        private set
    val skipped: MutableMap<String, Int> = sortedMapOf()
    private val messages = mutableListOf<TranscriptMessage>()
    private val toolNames = HashMap<String, String>()
    private var pending: PendingAssistant? = null

    /** [record] is null for a line that did not parse. */
    fun accept(record: JsonObject?): Boolean {
        val messageId = (record?.get("message") as? JsonObject)?.let { JsonScalars.str(it, "id") }
        val continues = pending != null && messageId != null && messageId == pending?.id
        val full = messages.size + (if (pending != null) 1 else 0) >= limit
        if (!continues && full) return false
        if (!continues) flush()
        return when {
            record == null -> count(SKIPPED_UNPARSEABLE)
            record["isSidechain"] == JsonPrimitive(true) -> count(SKIPPED_SIDECHAIN)
            record["isApiErrorMessage"] == JsonPrimitive(true) -> apiError(record)
            else -> conversation(record, messageId)
        }
    }

    fun finish(): List<TranscriptMessage> {
        flush()
        return messages
    }

    private fun conversation(record: JsonObject, messageId: String?): Boolean {
        val type = JsonScalars.str(record, "type") ?: "untyped"
        val message = record["message"] as? JsonObject
        val ts = timestamp(record)
        return when {
            type == "assistant" && message != null -> assistant(message, messageId, ts)
            type == "user" && message != null -> user(record, message, ts)
            type == "system" -> system(record, ts)
            else -> count(type)
        }
    }

    private fun assistant(message: JsonObject, messageId: String?, ts: Long?): Boolean {
        val into = pending?.takeIf { it.id == messageId } ?: PendingAssistant(messageId, ts).also { pending = it }
        for (block in blocks(message)) {
            when (JsonScalars.str(block, "type")) {
                "text" -> JsonScalars.str(block, "text")?.let(into.texts::add)
                "tool_use" -> {
                    val name = JsonScalars.str(block, "name") ?: "tool"
                    JsonScalars.str(block, "id")?.let { toolNames[it] = name }
                    into.calls += name to (block["input"]?.toString() ?: "{}")
                }
                // Thinking is the model's working, not its output; the conversation shows what it said.
                else -> Unit
            }
        }
        return true
    }

    private fun user(record: JsonObject, message: JsonObject, ts: Long?): Boolean {
        val meta = record["isMeta"] == JsonPrimitive(true) || record["isCompactSummary"] == JsonPrimitive(true)
        val speaker = if (meta) TranscriptRole.SYSTEM else TranscriptRole.USER
        val content = message["content"]
        if (content is JsonPrimitive && content.isString) {
            emit(speaker, ts, content.content)
            return true
        }
        for (block in blocks(message)) {
            when (JsonScalars.str(block, "type")) {
                "tool_result" -> {
                    val name = JsonScalars.str(block, "tool_use_id")?.let(toolNames::get)
                    emit(TranscriptRole.TOOL, ts, resultText(block["content"]), tool = name, result = true)
                }
                "text" -> JsonScalars.str(block, "text")?.let { emit(speaker, ts, it) }
                else -> count("user:${JsonScalars.str(block, "type") ?: "block"}")
            }
        }
        return true
    }

    private fun system(record: JsonObject, ts: Long?): Boolean {
        val text = (record["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text.isNullOrBlank()) return count("system:${JsonScalars.str(record, "subtype") ?: "untyped"}")
        emit(TranscriptRole.SYSTEM, ts, text)
        return true
    }

    private fun apiError(record: JsonObject): Boolean {
        val message = record["message"] as? JsonObject
        val text = message?.let { blocks(it).mapNotNull { b -> JsonScalars.str(b, "text") }.joinToString("\n") }
            ?.takeIf { it.isNotBlank() }
            ?: JsonScalars.str(record, "error")
            ?: "the client recorded an API error with no text"
        emit(TranscriptRole.SYSTEM, timestamp(record), "API error: $text")
        return true
    }

    private fun flush() {
        val done = pending ?: return
        pending = null
        if (done.texts.isNotEmpty()) emit(TranscriptRole.ASSISTANT, done.ts, done.texts.joinToString("\n\n"))
        for ((name, input) in done.calls) emit(TranscriptRole.ASSISTANT, done.ts, input, tool = name, result = false)
    }

    private fun emit(role: TranscriptRole, ts: Long?, text: String, tool: String? = null, result: Boolean? = null) {
        messages += TranscriptMessage(nextIndex, role, ts, redaction.shown(text), tool, result)
        nextIndex += 1
    }

    private fun count(kind: String): Boolean {
        skipped[kind] = (skipped[kind] ?: 0) + 1
        return true
    }

    private fun blocks(message: JsonObject): List<JsonObject> =
        (message["content"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun resultText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.content
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.let { b -> JsonScalars.str(b, "text") } }.joinToString("\n")
        else -> ""
    }

    private fun timestamp(record: JsonObject): Long? = JsonScalars.str(record, "timestamp")?.let { raw ->
        // ast-grep-ignore: kt-no-silent-result-collapse -- ts is optional in the contract; a record whose timestamp does not parse simply carries none
        Cancellables.runCatchingCancellable { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
    }

    private class PendingAssistant(val id: String?, val ts: Long?) {
        val texts = mutableListOf<String>()
        val calls = mutableListOf<Pair<String, String>>()
    }
}

/** Credential shapes only (the doctor report's set, DoctorRedaction): this is the operator's own
 *  conversation, so paths, ids and addresses stay readable and only secrets are masked. */
private class TranscriptRedaction {
    private val jwt = Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}")
    private val bearer = Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val keyValue = Regex(
        "(?i)\\b([a-z0-9_-]*(?:api_?key|token|secret|password|passwd|cookie|credential|authorization)" +
            "[a-z0-9_-]*)(\"?\\s*[=:]\\s*\"?)[^\\s\"',}]{8,}",
    )
    private val providerKey = Regex("\\b(sk|xai|gsk|xoxb|ghp|github_pat)[-_][A-Za-z0-9_-]{16,}")

    /** [value] as a page shows it: clipped to MAX_TEXT_CHARS, then redacted. */
    fun shown(value: String): String = text(clip(value))

    private fun clip(text: String): String =
        if (text.length <= MAX_TEXT_CHARS) {
            text
        } else {
            text.take(MAX_TEXT_CHARS) + "\n… [cut: ${text.length - MAX_TEXT_CHARS} more characters]"
        }

    fun text(value: String): String = value
        .replace(jwt, "[redacted jwt]")
        .replace(bearer, "Bearer [redacted]")
        .replace(keyValue) { "${it.groupValues[1]}${it.groupValues[2]}[redacted]" }
        .replace(providerKey, "[redacted key]")
}
