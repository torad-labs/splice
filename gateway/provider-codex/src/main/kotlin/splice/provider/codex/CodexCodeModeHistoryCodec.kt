// NEW: projects and rebuilds persisted Codex code-mode history without changing wire item bytes.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.dialect.responses.ResponsesCodeModeInput
import splice.dialect.responses.ResponsesCodeModeProjection
import java.security.MessageDigest

/**
 * Every persisted count, digest and offset is CONVERSATION-relative: the lite preamble (the leading
 * developer-role items — `additional_tools` and the base instructions) is split off before a record
 * is measured and put back after a rewrite. The preamble is environment, not history: Claude Code
 * grows its tool list mid-conversation (ToolSearch loading a deferred schema, an MCP server
 * reconnecting) and its system prompt moves too. The 2026-09-07 live failure was two sessions whose
 * tool count went 35 -> 36 one turn after a completed script; the whole-input digest then refused
 * every later turn with "logical history does not match its persisted baseline".
 */
internal class CodexCodeModeHistoryCodec(private val json: Json) {
    val projection = ResponsesCodeModeProjection()

    fun inputBoundary(bodyJson: String): CodeModeInputBoundary? {
        val input = root(bodyJson)?.second ?: return null
        val rawBody = input.dropWhile(::isPreamble)
        val body = conversation(projection.project(input)).body
        return CodeModeInputBoundary(
            fullCount = rawBody.size,
            logicalCount = body.logicalItems.size,
            logicalDigest = digest(JsonArray(body.logicalItems).toString()),
            fullDigest = digest(JsonArray(rawBody).toString()),
            nativeSegments = body.nativeSegments.map {
                CodeModeNativeSegment(it.logicalOffset, it.items)
            },
        )
    }

    /** Splits the projected input into its lite preamble and the conversation the records measure. */
    fun conversation(input: ResponsesCodeModeInput): CodeModeConversation {
        val preamble = input.logicalItems.takeWhile(::isPreamble)
        val offset = preamble.size
        val replay = input.replayItems.map { it.copy(logicalOffset = maxOf(0, it.logicalOffset - offset)) }
        return CodeModeConversation(preamble, ResponsesCodeModeInput(input.logicalItems.drop(offset), replay))
    }

    fun validPrefix(items: List<JsonElement>, record: CodeModeRecord): Boolean {
        if (items.size < record.baselineLogicalCount) return false
        return digest(JsonArray(items.take(record.baselineLogicalCount)).toString()) == record.baselineLogicalDigest
    }

    fun validFullPrefix(input: JsonArray, record: CodeModeRecord): Boolean {
        val rawBody = input.dropWhile(::isPreamble)
        if (rawBody.size < record.baselineInputCount) return false
        return digest(JsonArray(rawBody.take(record.baselineInputCount)).toString()) == record.baselineInputDigest
    }

    fun rebuilt(root: JsonObject, conversation: CodeModeConversation, body: ResponsesCodeModeInput): CodeModeRewrite {
        val offset = conversation.preamble.size
        val joined = ResponsesCodeModeInput(
            conversation.preamble + body.logicalItems,
            body.replayItems.map { it.copy(logicalOffset = it.logicalOffset + offset) },
        )
        return CodeModeRewrite(JsonObject(root + (FIELD_INPUT to projection.rebuild(joined))).toString())
    }

    fun root(bodyJson: String): Pair<JsonObject, JsonArray>? {
        val root = json.parseToJsonElement(bodyJson) as? JsonObject ?: return null
        val input = root[FIELD_INPUT] as? JsonArray ?: return null
        return root to input
    }

    fun customOutput(record: CodeModeRecord): JsonObject = buildJsonObject {
        put(FIELD_TYPE, TYPE_CUSTOM_OUTPUT)
        put(FIELD_CALL_ID, record.outerCallId)
        put(FIELD_OUTPUT, record.output.orEmpty())
    }

    fun callId(element: JsonElement): String? =
        (element as? JsonObject)?.let { JsonScalars.str(it, FIELD_CALL_ID) }

    fun string(item: JsonObject?, key: String): String = JsonScalars.str(item, key).orEmpty()

    private fun isPreamble(element: JsonElement): Boolean {
        val item = element as? JsonObject ?: return false
        return string(item, FIELD_ROLE) == ROLE_DEVELOPER && string(item, FIELD_CALL_ID).isEmpty()
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

/** The lite preamble a request arrived with, and the conversation body every record is measured on. */
internal data class CodeModeConversation(
    val preamble: List<JsonElement>,
    val body: ResponsesCodeModeInput,
)

internal const val CODE_MODE_FIELD_CALL_ID = "call_id"
internal const val CODE_MODE_FIELD_TYPE = "type"
private const val FIELD_INPUT = "input"
private const val FIELD_OUTPUT = "output"
private const val FIELD_ROLE = "role"
private const val ROLE_DEVELOPER = "developer"
private const val FIELD_CALL_ID = CODE_MODE_FIELD_CALL_ID
private const val FIELD_TYPE = CODE_MODE_FIELD_TYPE
private const val TYPE_CUSTOM_OUTPUT = "custom_tool_call_output"
