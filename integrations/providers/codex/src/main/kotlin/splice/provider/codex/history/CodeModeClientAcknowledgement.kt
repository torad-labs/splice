package splice.provider.codex.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.perf.InputDigest
import splice.core.util.JsonScalars
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeProjection

/** An observed client prefix, independent of the model's private item counts and display block grouping.
 * Only hashes and character counts are retained; replay ownership uses native item identity, never prose. */
@Serializable
internal data class CodeModeClientAcknowledgement(
    val items: List<CodeModeAcknowledgedItem>,
    val native: List<String>,
) {
    /** The end of the acknowledged logical prefix, or null after a rewind or edit. */
    fun boundary(input: ResponsesCodeModeInput, clearedCallbacks: Set<String> = emptySet()): Int? =
        consume(input, clearedCallbacks)?.takeIf { it.characters == 0 }?.item

    /** A coalesced assistant message may hold the end of this prefix and the beginning of its successor. */
    fun consume(input: ResponsesCodeModeInput, clearedCallbacks: Set<String> = emptySet()): CodeModeClientCursor? =
        CodeModeAcknowledgedPrefix(input.logicalItems, clearedCallbacks).consume(items)

    fun owns(item: JsonElement): Boolean = CodeModeClientAcknowledgements.identity(item) in native
}

internal object CodeModeClientAcknowledgements {
    private val projection = ResponsesCodeModeProjection()

    fun capture(
        items: List<JsonElement>,
        media: Map<String, List<JsonElement>> = emptyMap(),
    ): CodeModeClientAcknowledgement = capture(projection.project(JsonArray(items)), media)

    fun capture(
        input: ResponsesCodeModeInput,
        media: Map<String, List<JsonElement>> = emptyMap(),
    ): CodeModeClientAcknowledgement {
        val owners = CodeModeAcknowledgedMedia.indexes(input.logicalItems, media)
        val items = mutableListOf<CodeModeAcknowledgedItem>()
        var at = 0
        while (at < input.logicalItems.size) {
            val first = CodeModeClientAcknowledgements.assistantText(input.logicalItems[at])
            if (first == null) {
                val item = input.logicalItems[at++]
                val value = item as? JsonObject
                val callback = if (JsonScalars.str(value, "type") == "function_call_output") {
                    JsonScalars.str(value, "call_id")
                } else {
                    null
                }
                items += CodeModeAcknowledgedItem(
                    InputDigest.hex(item),
                    callbackId = callback,
                    mediaCallbackId = owners[at - 1],
                )
            } else {
                val text = StringBuilder(first)
                at++
                while (at < input.logicalItems.size) {
                    val next = CodeModeClientAcknowledgements.assistantText(input.logicalItems[at]) ?: break
                    text.append(next)
                    at++
                }
                items += CodeModeAcknowledgedItem(InputDigest.hex(text.toString()), text.length)
            }
        }
        return CodeModeClientAcknowledgement(items, input.replayItems.flatMap { it.items }.map(::identity))
    }

    fun identity(item: JsonElement): String = InputDigest.hex(CodeModeReasoningIdentity.key(item))

    fun assistantText(item: JsonElement?): String? {
        val message = item as? JsonObject ?: return null
        if (JsonScalars.str(message, "role") != "assistant") return null
        return (message["content"] as? JsonPrimitive)?.takeIf { it.isString }?.let(JsonScalars::str)
    }
}

@Serializable
internal data class CodeModeAcknowledgedItem(
    val digest: String,
    val characters: Int? = null,
    val callbackId: String? = null,
    val mediaCallbackId: String? = null,
)

internal data class CodeModeClientCursor(val item: Int, val characters: Int = 0)

internal object CodeModeAcknowledgedMedia {
    fun indexes(items: List<JsonElement>, media: Map<String, List<JsonElement>>): Map<Int, String> = buildMap {
        items.forEachIndexed { index, element ->
            val item = element as? JsonObject
            val id = JsonScalars.str(item, "call_id")
            val expected = media[id].orEmpty()
            val output = JsonScalars.str(item, "type") == "function_call_output"
            val captured = output && expected.isNotEmpty() && items.drop(index + 1).take(expected.size) == expected
            if (captured) {
                expected.indices.forEach { put(index + 1 + it, checkNotNull(id)) }
            }
        }
    }
}
