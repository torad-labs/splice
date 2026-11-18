// NEW: one way a text is added to a user message on the anthropic passthrough wire, shared by the compaction tail
// (PassthroughCompactionTail) and a capability's text (PassthroughFirstUserText, docs/specs/harness.md decision 5),
// so the two can't drift: one more text block after the client's, which keep their bytes; a content the client
// sent as a bare string is that string's one text block first.
package splice.dialect.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class PassthroughUserContent {
    /** [content] with a text block of [text] at its end, or null where it is neither blocks nor a string. */
    public fun extended(content: JsonElement?, text: String): JsonArray? = when {
        content is JsonArray -> JsonArray(content + textBlock(text))
        content is JsonPrimitive && content.isString -> JsonArray(listOf(textBlock(content.content), textBlock(text)))
        else -> null
    }

    private fun textBlock(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }
}
