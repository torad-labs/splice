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

internal class CodexCodeModeHistoryCodec(private val json: Json) {
    val projection = ResponsesCodeModeProjection()

    fun inputBoundary(bodyJson: String): CodeModeInputBoundary? {
        val input = root(bodyJson)?.second ?: return null
        val projected = projection.project(input)
        return CodeModeInputBoundary(
            fullCount = input.size,
            logicalCount = projected.logicalItems.size,
            logicalDigest = digest(JsonArray(projected.logicalItems).toString()),
            fullDigest = digest(input.toString()),
            nativeSegments = projected.nativeSegments.map {
                CodeModeNativeSegment(it.logicalOffset, it.items)
            },
        )
    }

    fun validPrefix(items: List<JsonElement>, record: CodeModeRecord): Boolean {
        if (items.size < record.baselineLogicalCount) return false
        return digest(JsonArray(items.take(record.baselineLogicalCount)).toString()) == record.baselineLogicalDigest
    }

    fun validFullPrefix(input: JsonArray, record: CodeModeRecord): Boolean {
        if (input.size < record.baselineInputCount) return false
        return digest(JsonArray(input.take(record.baselineInputCount)).toString()) == record.baselineInputDigest
    }

    fun rebuilt(root: JsonObject, input: ResponsesCodeModeInput): CodeModeRewrite =
        CodeModeRewrite(JsonObject(root + (FIELD_INPUT to projection.rebuild(input))).toString())

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

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal const val CODE_MODE_FIELD_CALL_ID = "call_id"
internal const val CODE_MODE_FIELD_TYPE = "type"
private const val FIELD_INPUT = "input"
private const val FIELD_OUTPUT = "output"
private const val FIELD_CALL_ID = CODE_MODE_FIELD_CALL_ID
private const val FIELD_TYPE = CODE_MODE_FIELD_TYPE
private const val TYPE_CUSTOM_OUTPUT = "custom_tool_call_output"
