// NEW: one way a text is added to a user message on the openai-chat wire, shared by the compaction tail
// (ChatCompactionTail) and a capability's text (ChatFirstUserText, docs/specs/harness.md decision 5), so the two
// can't drift: one more text part where the content is parts, a paragraph after the text where it is a string.
package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars

public class ChatUserContent {
    /** [content] with [text] added at its end, or null where it is neither parts nor a string. */
    public fun extended(content: JsonElement?, text: String): JsonElement? = when {
        content is JsonArray -> JsonArray(
            content + buildJsonObject {
                put("type", "text")
                put("text", text)
            },
        )
        content is JsonPrimitive && content.isString -> {
            val original = JsonScalars.strIfString(content)
            JsonPrimitive(if (original.isEmpty()) text else "$original\n\n$text")
        }
        else -> null
    }
}
