package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars

/** Reads native message text without copying a single existing text part. */
public object ResponsesMessageText {
    internal fun parts(item: JsonObject): List<String> = when (val content = item["content"]) {
        is JsonArray -> content.mapNotNull { JsonScalars.str(it as? JsonObject, "text") }.filter { it.isNotEmpty() }
        is JsonPrimitive -> listOfNotNull(JsonScalars.str(content)).filter { it.isNotEmpty() }
        is JsonObject, null -> emptyList()
    }

    /** Scalar legacy content and ordered text parts share the same native message grammar. */
    public fun text(item: JsonObject): String {
        val parts = parts(item)
        return if (parts.size == 1) parts.single() else parts.joinToString("")
    }
}
