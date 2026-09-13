package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Appends the custom text as the final user message, after the client's summarizer prompt. */
public class ChatCompactionTail {
    public fun append(request: JsonObject, text: String): JsonObject {
        if (text.isEmpty()) return request
        val messages = request["messages"] as? JsonArray ?: return request
        val fields = request.toMutableMap()
        fields["messages"] = JsonArray(
            messages + buildJsonObject {
                put("role", "user")
                put("content", text)
            },
        )
        return JsonObject(fields)
    }
}
