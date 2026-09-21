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
//
// 2026-09-18 (V4-160, concentration): the public types moved to TranscriptTypes.kt, the page assembly
// to TranscriptAssembly.kt and the redaction to TranscriptRedaction.kt; same package, same behaviour.
package splice.client.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.client.Keys
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/** Bytes one page may read before it stops, whatever it found: a run of skipped records must not
 *  turn one page into a full-file read. */
private const val MAX_PAGE_BYTES = 16L shl 20

/** One line this long is not a conversation record; it is read past, not kept, and counted. */
private const val MAX_LINE_BYTES = 32 shl 20

private const val BAD_ID = "not a session id"
private const val SEND_MESSAGE = "SendMessage"
private const val BAD_CURSOR = "not a cursor this daemon minted"

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
            ?: return TranscriptLookup.Missing(roots.map { it.resolve(Keys.PROJECTS).toString() })
        return TranscriptLookup.Found(read(file, sessionId, start, limit.coerceIn(1, MAX_TRANSCRIPT_PAGE)))
    }

    /** The `message` of every SendMessage call in [sessionId]'s transcript whose tool_use id is in
     *  [ids] (see the header). A malformed session id finds nothing and reports every id missing. */
    public fun sentTexts(sessionId: String, head: String?, ids: Set<String>): SentTexts {
        val roots = trees(head)
        val file = if (validSessionId.matches(sessionId)) locate(roots, sessionId) else null
        if (file == null) return SentTexts(null, emptyMap(), ids, roots.map { it.resolve(Keys.PROJECTS).toString() })
        val found = HashMap<String, String>()
        Files.newInputStream(file).use { raw ->
            val input = BufferedInputStream(raw)
            while (found.size < ids.size) {
                val line = nextLine(input) ?: break
                collect(line, ids, found)
            }
        }
        return SentTexts(file.toString(), found, ids - found.keys)
    }

    /** One line's wanted sends into [found]. The line is parsed only when its bytes name an id that is
     *  not found yet, so the pass costs a byte scan, not a parse per line. */
    private fun collect(line: Line, ids: Set<String>, found: MutableMap<String, String>) {
        val text = line.text() ?: return
        if (ids.none { it !in found && text.contains(it) }) return
        val record = parse(checkNotNull(line.bytes)) ?: return
        sends(record, ids).forEach { (id, sent) -> found.putIfAbsent(id, redaction.shown(sent)) }
    }

    /** The wanted SendMessage calls of one assistant record, id to message. A `message` that is not
     *  a string (a structured request) is shown as its JSON. */
    private fun sends(record: JsonObject, ids: Set<String>): List<Pair<String, String>> {
        val message = record[MESSAGE] as? JsonObject
        val blocks = (message?.get("content") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        return blocks
            .filter { JsonScalars.str(it, "type") == "tool_use" && JsonScalars.str(it, "name") == SEND_MESSAGE }
            .mapNotNull { block -> JsonScalars.str(block, "id")?.takeIf { it in ids }?.let { it to block } }
            .map { (id, block) ->
                val sent = (block["input"] as? JsonObject)?.get(MESSAGE)
                id to ((sent as? JsonPrimitive)?.takeIf { it.isString }?.content ?: sent?.toString().orEmpty())
            }
    }

    /** The first candidate in root order. Each projects dir is walked once, however many root names
     *  reach it: the symlinked heads' dirs resolve to the vanilla one and are dropped as repeats. */
    private fun locate(roots: List<Path>, sessionId: String): Path? {
        val walked = HashSet<Path>()
        return roots.asSequence()
            .map { it.resolve(Keys.PROJECTS) }
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

    /** Lines from [start] until the page is full, the byte budget is spent or the file ends; a line the
     *  assembly declines (it starts the next page's first message) is left unread for that page. */
    private fun read(file: Path, sessionId: String, start: Position, limit: Int): TranscriptPage {
        val assembly = PageAssembly(start.index, limit, redaction)
        val size = Files.size(file)
        val from = start.offset.coerceAtMost(size)
        var offset = from
        Files.newInputStream(file).use { raw ->
            val input = BufferedInputStream(raw)
            input.skipNBytes(from)
            var more = true
            while (more && offset - from < MAX_PAGE_BYTES) {
                val taken = nextLine(input)?.takeIf { assembly.accept(it.bytes?.let(::parse)) }
                more = taken != null
                offset += taken?.length ?: 0L
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

    private class Line(val bytes: ByteArray?, val length: Long) {
        /** The line as text, or null when it was read past for its length. */
        fun text(): String? = bytes?.toString(Charsets.UTF_8)
    }
}
