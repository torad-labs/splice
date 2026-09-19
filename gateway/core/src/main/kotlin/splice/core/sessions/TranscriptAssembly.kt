// NEW: V4-160 — records folded into a page's conversation messages, moved out of TranscriptReader.kt
// (concentration and complexity, 2026-09-18). Behaviour is unchanged: accept, assistant and user had
// their branches lifted into named helpers, and the record-reading helpers became TranscriptRecords
// so the assembly stays under the function ceiling. TranscriptReader.kt's header states the rules.
package splice.core.sessions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.Cancellables
import splice.core.util.JsonScalars

private const val MESSAGE = "message"
private const val UNTYPED = "untyped"
private const val CONTENT = "content"

/** Folds records into conversation messages for one page. [accept] answers false when the page is
 *  full AND the record starts a new message, so the record is left for the next page. */
internal class PageAssembly(firstIndex: Long, private val limit: Int, private val redaction: TranscriptRedaction) {
    var nextIndex: Long = firstIndex
        private set
    val skipped: MutableMap<String, Int> = sortedMapOf()
    private val messages = mutableListOf<TranscriptMessage>()
    private val toolNames = HashMap<String, String>()
    private val records = TranscriptRecords()
    private var pending: PendingAssistant? = null

    /** [record] is null for a line that did not parse. */
    fun accept(record: JsonObject?): Boolean {
        val messageId = (record?.get(MESSAGE) as? JsonObject)?.let { JsonScalars.str(it, "id") }
        val continues = continues(messageId)
        if (!continues && full()) return false
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

    /** The record's message id is the pending assistant message's: another block of the same message. */
    private fun continues(messageId: String?): Boolean = messageId != null && messageId == pending?.id

    private fun full(): Boolean = messages.size + (if (pending != null) 1 else 0) >= limit

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
        val into = pending?.takeIf { it.id == messageId } ?: PendingAssistant(messageId, ts).also { pending = it }
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
        JsonScalars.str(block, "id")?.let { toolNames[it] = name }
        into.calls += name to (block["input"]?.toString() ?: "{}")
    }

    private fun user(record: JsonObject, message: JsonObject, ts: Long?): Boolean {
        val meta = record["isMeta"] == JsonPrimitive(true) || record["isCompactSummary"] == JsonPrimitive(true)
        val speaker = if (meta) TranscriptRole.SYSTEM else TranscriptRole.USER
        val content = message[CONTENT]
        if (content is JsonPrimitive && content.isString) {
            emit(speaker, ts, content.content)
            return true
        }
        for (block in records.blocks(message)) userBlock(block, speaker, ts)
        return true
    }

    private fun userBlock(block: JsonObject, speaker: TranscriptRole, ts: Long?) {
        when (JsonScalars.str(block, "type")) {
            "tool_result" -> {
                val name = JsonScalars.str(block, "tool_use_id")?.let(toolNames::get)
                emit(TranscriptRole.TOOL, ts, records.resultText(block[CONTENT]), tool = name, result = true)
            }
            "text" -> JsonScalars.str(block, "text")?.let { emit(speaker, ts, it) }
            else -> count("user:${JsonScalars.str(block, "type") ?: "block"}")
        }
    }

    private fun system(record: JsonObject, ts: Long?): Boolean {
        val text = (record[CONTENT] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text.isNullOrBlank()) return count("system:${JsonScalars.str(record, "subtype") ?: UNTYPED}")
        emit(TranscriptRole.SYSTEM, ts, text)
        return true
    }

    private fun apiError(record: JsonObject): Boolean {
        val message = record[MESSAGE] as? JsonObject
        val text = message?.let { records.blocks(it).mapNotNull { b -> JsonScalars.str(b, "text") }.joinToString("\n") }
            ?.takeIf { it.isNotBlank() }
            ?: JsonScalars.str(record, "error")
            ?: "the client recorded an API error with no text"
        emit(TranscriptRole.SYSTEM, records.timestamp(record), "API error: $text")
        return true
    }

    private fun flush() {
        val done = pending ?: return
        pending = null
        if (done.texts.isNotEmpty()) emit(TranscriptRole.ASSISTANT, done.ts, done.joined())
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

    private class PendingAssistant(val id: String?, val ts: Long?) {
        val texts = mutableListOf<String>()
        val calls = mutableListOf<Pair<String, String>>()

        /** The message's text blocks as one text, in the order the client wrote them. */
        fun joined(): String = texts.joinToString("\n\n")
    }
}

/** Reads the parts of a transcript record the assembly needs; no state. */
internal class TranscriptRecords {

    fun blocks(message: JsonObject): List<JsonObject> =
        (message[CONTENT] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    fun resultText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.content
        is JsonArray -> joinedTexts(content)
        else -> ""
    }

    private fun joinedTexts(blocks: JsonArray): String =
        blocks.mapNotNull { (it as? JsonObject)?.let { b -> JsonScalars.str(b, "text") } }.joinToString("\n")

    fun timestamp(record: JsonObject): Long? = JsonScalars.str(record, "timestamp")?.let { raw ->
        // ast-grep-ignore: kt-no-silent-result-collapse -- ts is optional in the contract; a record whose timestamp does not parse simply carries none
        Cancellables.runCatchingCancellable { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
    }
}
