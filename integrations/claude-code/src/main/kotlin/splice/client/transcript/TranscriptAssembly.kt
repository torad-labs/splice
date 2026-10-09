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
import splice.sessions.transcript.SKIPPED_SIDECHAIN
import splice.sessions.transcript.SKIPPED_UNPARSEABLE
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptRole

private const val UNTYPED = "untyped"

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
        return ledger.all()
    }

    val pendingOffset: Long? get() = pending?.at

    /** Completed messages only. An incremental metadata index can count them without flushing a split reply. */
    fun drain(): List<TranscriptMessage> = ledger.drain()

    /** The record's message id is the pending assistant message's: another block of the same message. */
    private val full: Boolean get() = ledger.size + (if (pending != null) 1 else 0) >= limit

    private fun conversation(record: JsonObject, messageId: String?): Boolean {
        val type = JsonScalars.str(record, "type") ?: UNTYPED
        val message = record[MESSAGE] as? JsonObject
        val ts = records.timestamp(record)
        return when {
            type == "assistant" && message != null -> assistant(message, messageId, ts)
            type == "user" && message != null -> user(record, message, ts)
            type == "system" -> system(record, ts)
            else -> count(type)
        }
    }

    private fun assistant(message: JsonObject, messageId: String?, ts: Long?): Boolean {
        val into = pending?.takeIf { it.id == messageId } ?: PendingAssistant(messageId, ts, at).also { pending = it }
        for (block in records.blocks(message)) {
            when (JsonScalars.str(block, "type")) {
                "text" -> JsonScalars.str(block, "text")?.let(into.texts::add)
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
            ledger.text(at, speaker, ts, content.content)
            return true
        }
        for (block in records.blocks(message)) userBlock(block, speaker, ts)
        return true
    }

    private fun userBlock(block: JsonObject, speaker: TranscriptRole, ts: Long?) {
        when (JsonScalars.str(block, "type")) {
            "tool_result" -> {
                val id = JsonScalars.str(block, "tool_use_id")
                ledger.toolResult(at, ts, records.resultText(block[CONTENT]), id)
            }
            "text" -> JsonScalars.str(block, "text")?.let { ledger.text(at, speaker, ts, it) }
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
        ledger.text(at, TranscriptRole.SYSTEM, records.timestamp(record), "API error: $text")
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
