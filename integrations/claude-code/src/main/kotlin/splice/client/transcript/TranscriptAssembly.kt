// NEW: V4-160 — records folded into a page's conversation messages, moved out of TranscriptReader.kt
// (concentration and complexity, 2026-09-18). Behaviour is unchanged: accept, assistant and user had
// their branches lifted into named helpers, and the record-reading helpers became TranscriptRecords
// so the assembly stays under the function ceiling. TranscriptReader.kt's header states the rules.
package splice.client.transcript

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.sessions.transcript.KIND_TAKEN_BACK
import splice.sessions.transcript.SKIPPED_SIDECHAIN
import splice.sessions.transcript.SKIPPED_UNPARSEABLE
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptRole

private const val API_ERROR = "API error"
private const val UNTYPED = "untyped"

/** The model Claude Code files on a reply it wrote itself, such as the "No response requested." it adds on resuming a
 *  turn nobody answered. Nobody said it, so it is counted, not shown; its real API errors are read before this. */
private const val SYNTHETIC = "<synthetic>"

/** How Claude Code opens the text it files when a turn is stopped or a tool call refused, whether or not it goes on to
 *  say "for tool use". */
private const val INTERRUPTED_MARKER = "[Request interrupted by user"

// why: the most messages one record may make; they share its offset and are told apart by this many slots.
internal const val PER_RECORD: Long = 1024

/** Folds records into conversation messages for one page. [accept] answers false when the page is
 *  full AND the record starts a new message, so the record is left for the next page. */
internal class PageAssembly(
    firstIndex: Long,
    private val limit: Int,
    private val redaction: TranscriptRedaction,
    /** True: a message is numbered by where its record starts in the file, [at] * [PER_RECORD] plus its place among the
     *  messages that record made, so pages read from either end never repeat or reorder an index. */
    private val byOffset: Boolean = false,
) {
    /** The byte offset of the record [accept] is about to read; only a [byOffset] assembly numbers by it. */
    var at: Long = 0
    private val ledger = MessageLedger(firstIndex, redaction, byOffset)
    val nextIndex: Long get() = ledger.nextIndex
    val skipped: MutableMap<String, Int> = sortedMapOf()
    private val records = TranscriptRecords()
    private val peers = PeerEnvelope()
    private val echoes = ClientEcho()
    private val lineage = TranscriptLineage()
    private var pending: PendingAssistant? = null

    /** [record] is null for a line that did not parse. */
    fun accept(record: JsonObject?): Boolean {
        val messageId = records.messageId(record)
        val continues = messageId != null && messageId == pending?.id
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
        val taken = lineage.takenBack()
        if (taken.isEmpty()) return ledger.all()
        return ledger.all().map { message ->
            if (message.index in taken) message.copy(source = message.source.copy(kind = KIND_TAKEN_BACK)) else message
        }
    }

    val pendingOffset: Long? get() = pending?.at

    /** Completed messages only. An incremental metadata index can count them without flushing a split reply. */
    fun drain(): List<TranscriptMessage> = ledger.drain()

    /** The record's message id is the pending assistant message's: another block of the same message. */
    private val full: Boolean get() = ledger.size + (if (pending != null) 1 else 0) >= limit

    private fun conversation(record: JsonObject, messageId: String?): Boolean {
        lineage.note(record)
        val type = JsonScalars.str(record, "type") ?: UNTYPED
        val message = record[MESSAGE] as? JsonObject
        val ts = records.timestamp(record)
        return when {
            message?.get("model") == JsonPrimitive(SYNTHETIC) -> count("synthetic")
            type == "assistant" && message != null -> assistant(message, messageId, ts)
            type == "user" && message != null -> user(record, message, ts)
            type == "system" -> system(record, ts)
            else -> count(type)
        }
    }

    private fun assistant(message: JsonObject, messageId: String?, ts: Long?): Boolean {
        val into = pending?.takeIf { it.id == messageId } ?: PendingAssistant(messageId, ts, at).also { pending = it }
        if (into.model == null) into.model = records.model(message)
        for (block in records.blocks(message)) {
            when (JsonScalars.str(block, "type")) {
                "text" -> JsonScalars.str(block, "text")
                    ?.takeUnless { it.trim() == THINKING_STAND_IN }
                    ?.let(into.texts::add)
                "tool_use" -> call(block, into)
                // Thinking is the model's working, not its output; the conversation shows what it said.
                else -> Unit
            }
        }
        return true
    }

    private fun call(block: JsonObject, into: PendingAssistant) {
        val name = JsonScalars.str(block, "name") ?: "tool"
        val id = JsonScalars.str(block, "id")
        id?.let { ledger.rememberTool(it, name) }
        into.calls += RecordedCall(id, name, block["input"]?.toString() ?: "{}")
    }

    private fun user(record: JsonObject, message: JsonObject, ts: Long?): Boolean {
        val meta = record["isMeta"] == JsonPrimitive(true) || record["isCompactSummary"] == JsonPrimitive(true)
        val speaker = if (meta) TranscriptRole.SYSTEM else TranscriptRole.USER
        val content = message[CONTENT]
        if (content is JsonPrimitive && content.isString) {
            userText(record, speaker, ts, content.content)
            return true
        }
        for (block in records.blocks(message)) userBlock(record, block, speaker, ts)
        return true
    }

    /** What a user record's text is: a teammate's message, the marker of a stopped turn, or the person's words. */
    private fun userText(record: JsonObject, speaker: TranscriptRole, ts: Long?, text: String) {
        val received = peers.read(record, text)
        if (received != null) return ledger.received(at, ts, received.from, received.body)
        when (val echo = echoes.read(text)) {
            EchoReading.Hidden -> count("user:client-note")
            is EchoReading.Command -> person(record, ts, echo.line)
            is EchoReading.Output -> ledger.text(at, TranscriptRole.SYSTEM, ts, echo.text)
            is EchoReading.Plain ->
                if (echo.text.startsWith(INTERRUPTED_MARKER)) {
                    ledger.interrupted(at, ts, echo.text)
                } else if (speaker == TranscriptRole.USER) {
                    person(record, ts, echo.text)
                } else {
                    ledger.text(at, speaker, ts, echo.text)
                }
        }
    }

    private fun person(record: JsonObject, ts: Long?, text: String) {
        ledger.text(at, TranscriptRole.USER, ts, text)
        lineage.person(record, ledger.all().last().index)
    }

    private fun userBlock(record: JsonObject, block: JsonObject, speaker: TranscriptRole, ts: Long?) {
        when (JsonScalars.str(block, "type")) {
            "tool_result" -> {
                val id = JsonScalars.str(block, "tool_use_id")
                ledger.toolResult(at, ts, records.resultText(block[CONTENT]), id)
            }
            "text" -> JsonScalars.str(block, "text")?.let { userText(record, speaker, ts, it) }
            else -> count("user:${JsonScalars.str(block, "type") ?: "block"}")
        }
    }

    private fun system(record: JsonObject, ts: Long?): Boolean {
        val text = (record[CONTENT] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text.isNullOrBlank()) return count("system:${JsonScalars.str(record, "subtype") ?: UNTYPED}")
        ledger.text(at, TranscriptRole.SYSTEM, ts, text)
        return true
    }

    private fun apiError(record: JsonObject): Boolean {
        val message = record[MESSAGE] as? JsonObject
        val text = message?.let { records.blocks(it).mapNotNull { b -> JsonScalars.str(b, "text") }.joinToString("\n") }
            ?.takeIf { it.isNotBlank() }
            ?: JsonScalars.str(record, "error")
            ?: "the client recorded an API error with no text"
        // Claude Code's own words often open with "API Error:" already, and the page must not say it twice
        val said = if (text.startsWith(API_ERROR, ignoreCase = true)) text else "$API_ERROR: $text"
        ledger.text(at, TranscriptRole.SYSTEM, records.timestamp(record), said)
        return true
    }

    private fun flush() {
        val done = pending ?: return
        pending = null
        ledger.assistant(done)
    }

    private fun count(kind: String): Boolean {
        skipped[kind] = (skipped[kind] ?: 0) + 1
        return true
    }
}

/** Reads the parts of a transcript record the assembly needs; no state. */
internal class TranscriptRecords {

    /** The id of the message a record belongs to, when it carries one. */
    fun messageId(record: JsonObject?): String? =
        (record?.get(MESSAGE) as? JsonObject)?.let { JsonScalars.str(it, "id") }

    /** The model a message names. The client files its own local notices under a placeholder in angle brackets, which
     *  is no model. */
    fun model(message: JsonObject): String? = JsonScalars.str(message, "model")?.takeUnless { it.startsWith("<") }

    fun blocks(message: JsonObject): List<JsonObject> =
        (message[CONTENT] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    fun resultText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.content
        is JsonArray -> joinedTexts(content)
        null, is JsonObject -> ""
    }

    private fun joinedTexts(blocks: JsonArray): String =
        blocks.mapNotNull { (it as? JsonObject)?.let { b -> JsonScalars.str(b, "text") } }.joinToString("\n")

    fun timestamp(record: JsonObject): Long? = JsonScalars.str(record, "timestamp")?.let { raw ->
        // ts is optional in the contract; a record whose timestamp does not parse simply carries none.
        try {
            java.time.Instant.parse(raw).toEpochMilli()
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }
    }
}
