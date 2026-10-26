// PORT-OF: codex-rs/tools/src/tool_output.rs @ rust-v0.164.0.
package splice.upstream.codemode

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars

/**
 * Tool output values are distinct from dispatch failures; only explicitly identified MCP values hide private
 * metadata.
 */
public object CodeModeToolOutputs {
    public fun value(result: CodeModeResult): JsonElement {
        val payload = result.payload?.let(CodeModeText::jsonValue) ?: return JsonPrimitive(result.output)
        return if (result.origin == CodeModeResultOrigin.MCP && payload is JsonObject) {
            withoutMetadata(payload)
        } else {
            payload
        }
    }

    /** serde_json's release preserve_order map uses swap_remove for this source remove() call. */
    private fun withoutMetadata(value: JsonObject): JsonObject {
        val entries = value.entries.map { it.key to it.value }.toMutableList()
        val index = entries.indexOfFirst { it.first == "_meta" }
        if (index < 0) return value
        val last = entries.removeLast()
        if (index < entries.size) entries[index] = last
        return JsonObject(entries.toMap())
    }

    /** Function content values preserve nonblank text and media references in order, separated by newlines. */
    public fun content(items: List<JsonObject>): String = items.mapNotNull { item ->
        val value = when (JsonScalars.str(item, "type")) {
            "input_text" -> JsonScalars.str(item, "text")
            "input_image" -> JsonScalars.str(item, "image_url") ?: JsonScalars.str(item, "file_id")
            "input_audio" -> JsonScalars.str(item, "audio_url")
            else -> null
        }
        value?.takeUnless(CodeModeText::isBlank)
    }.joinToString("\n")
}
